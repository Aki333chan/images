using System.Linq;
using Aurum.Companion.Core;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Panel;
using Xunit;

namespace Aurum.Companion.Core.Tests;

/// <summary>
/// Очередь событий и правила повторов.
///
/// Это место, где мод переживает падение панели, не мешая игре. Проверяется
/// именно поведение при неудачах — счастливый путь тут наименее интересен.
/// </summary>
public class EventQueueTests
{
    private static GameEvent Event(string name) =>
        new(GameEventKind.Chat, "Steam_1", name);

    [Fact]
    public void События_забираются_в_порядке_поступления()
    {
        var queue = new EventQueue(10);
        queue.Enqueue(Event("первое"));
        queue.Enqueue(Event("второе"));

        var batch = queue.Take(10);
        Assert.Equal(new[] { "первое", "второе" }, batch.Select(e => e.PlayerName));
        Assert.Equal(0, queue.Count);
    }

    /// <remarks>
    /// Очередь переполняется только когда панель лежит; когда она поднимется,
    /// свежая картина полезнее вчерашней. Плюс расти без предела нельзя —
    /// съеденная модом память отнимается у мира.
    /// </remarks>
    [Fact]
    public void При_переполнении_выбрасывается_самое_старое()
    {
        var queue = new EventQueue(2);
        queue.Enqueue(Event("старое"));
        queue.Enqueue(Event("среднее"));
        queue.Enqueue(Event("новое"));

        Assert.Equal(2, queue.Count);
        Assert.Equal(1, queue.Dropped);
        Assert.Equal(new[] { "среднее", "новое" }, queue.Take(10).Select(e => e.PlayerName));
    }

    [Fact]
    public void Возврат_сохраняет_порядок_и_ставит_пачку_вперёд()
    {
        var queue = new EventQueue(10);
        queue.Enqueue(Event("первое"));
        queue.Enqueue(Event("второе"));
        var batch = queue.Take(1);

        queue.Enqueue(Event("третье"));
        queue.Requeue(batch);

        Assert.Equal(new[] { "первое", "второе", "третье" }, queue.Take(10).Select(e => e.PlayerName));
    }

    [Fact]
    public void Возврат_не_раздувает_очередь_сверх_предела()
    {
        var queue = new EventQueue(2);
        queue.Enqueue(Event("а"));
        var batch = queue.Take(1);
        queue.Enqueue(Event("б"));
        queue.Enqueue(Event("в"));

        queue.Requeue(batch);

        Assert.Equal(2, queue.Count);
    }

    [Fact]
    public void Пустой_возврат_ничего_не_делает()
    {
        var queue = new EventQueue(10);
        queue.Enqueue(Event("а"));
        queue.Requeue(new System.Collections.Generic.List<GameEvent>());
        Assert.Equal(1, queue.Count);
    }
}

/// <summary>Правило отступа при недоступной панели.</summary>
public class BackoffTests
{
    [Fact]
    public void Первая_попытка_идёт_без_задержки()
    {
        Assert.Equal(0, EventSender.BackoffMs(0));
    }

    [Fact]
    public void Задержка_удваивается()
    {
        Assert.Equal(2000, EventSender.BackoffMs(1));
        Assert.Equal(4000, EventSender.BackoffMs(2));
        Assert.Equal(8000, EventSender.BackoffMs(3));
    }

    /// <remarks>
    /// Потолок нужен, чтобы после долгого падения панели мод заметил её
    /// возвращение за минуту, а не через час, как вышло бы при
    /// неограниченном удвоении.
    /// </remarks>
    [Fact]
    public void Задержка_упирается_в_минуту()
    {
        Assert.Equal(60000, EventSender.BackoffMs(20));
        Assert.Equal(60000, EventSender.BackoffMs(1000));
    }
}

/// <summary>Что считать поводом для повтора.</summary>
public class PanelResponseTests
{
    [Theory]
    [InlineData(0)]    // сеть легла
    [InlineData(429)]  // слишком часто
    [InlineData(500)]
    [InlineData(503)]  // панель перезапускается
    public void Временные_неудачи_повторяются(int status)
    {
        Assert.True(new PanelResponse(status, "").IsRetryable);
    }

    /// <remarks>
    /// На 4xx повтор бессмысленен: панель поняла запрос и отказала. Сто
    /// повторов ничего не изменят, зато забьют очередь.
    /// </remarks>
    [Theory]
    [InlineData(400)]
    [InlineData(401)]
    [InlineData(403)]
    [InlineData(404)]
    public void Отказ_по_существу_не_повторяется(int status)
    {
        Assert.False(new PanelResponse(status, "").IsRetryable);
    }

    [Fact]
    public void Успех_есть_успех()
    {
        Assert.True(new PanelResponse(200, "").IsSuccess);
        Assert.True(new PanelResponse(204, "").IsSuccess);
        Assert.False(new PanelResponse(302, "").IsSuccess);
    }
}
