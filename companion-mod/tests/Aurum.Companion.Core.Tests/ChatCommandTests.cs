using Aurum.Companion.Core.Tickets;
using Xunit;

namespace Aurum.Companion.Core.Tests;

/// <summary>
/// Разбор игрового чата.
///
/// Это единственный вход в мод, доступный обычному игроку, — то есть
/// единственное место, куда произвольный человек шлёт произвольный текст.
/// Поэтому проверяется не только счастливый путь.
/// </summary>
public class ChatCommandTests
{
    [Fact]
    public void Обычное_сообщение_мод_не_трогает()
    {
        Assert.Equal(ChatCommandKind.None, ChatCommand.Parse("привет всем").Kind);
        Assert.Equal(ChatCommandKind.None, ChatCommand.Parse("").Kind);
        Assert.Equal(ChatCommandKind.None, ChatCommand.Parse(null).Kind);
        // Косая черта сама по себе — не команда.
        Assert.Equal(ChatCommandKind.None, ChatCommand.Parse("/").Kind);
    }

    [Fact]
    public void Чужая_команда_проходит_мимо()
    {
        // Команды других модов мод глотать не должен.
        Assert.Equal(ChatCommandKind.None, ChatCommand.Parse("/home").Kind);
        Assert.Equal(ChatCommandKind.None, ChatCommand.Parse("/tp 100 50 100").Kind);
    }

    [Fact]
    public void Тикет_разбирается()
    {
        var command = ChatCommand.Parse("/ticket пропали вещи после смерти");
        Assert.Equal(ChatCommandKind.Ticket, command.Kind);
        Assert.True(command.IsValid);
        Assert.Equal("пропали вещи после смерти", command.Text);
    }

    // Человек пишет это, стоя перед зомби, — разбор обязан быть терпимым.
    [Theory]
    [InlineData("/TICKET помогите")]
    [InlineData("/Ticket   помогите  ")]
    [InlineData("/тикет помогите")]
    public void Регистр_и_лишние_пробелы_не_мешают(string message)
    {
        var command = ChatCommand.Parse(message);
        Assert.Equal(ChatCommandKind.Ticket, command.Kind);
        Assert.True(command.IsValid);
        Assert.Equal("помогите", command.Text);
    }

    [Fact]
    public void Пустой_тикет_объясняет_как_надо()
    {
        var command = ChatCommand.Parse("/ticket");
        Assert.Equal(ChatCommandKind.Ticket, command.Kind);
        Assert.False(command.IsValid);
        Assert.Contains("/ticket", command.Problem);
    }

    [Fact]
    public void Слишком_длинное_обращение_отклоняется()
    {
        var command = ChatCommand.Parse("/ticket " + new string('а', ChatCommand.MaxTextLength + 1));
        Assert.False(command.IsValid);
        Assert.Contains("500", command.Problem);
    }

    // Перевод строки внутри сообщения умеет подделывать чужую строку журнала.
    [Fact]
    public void Управляющие_символы_вычищаются()
    {
        var command = ChatCommand.Parse("/ticket строка\nвторая\tтретья");
        Assert.True(command.IsValid);
        Assert.Equal("строка вторая третья", command.Text);
        Assert.DoesNotContain('\n', command.Text);
        Assert.DoesNotContain('\t', command.Text);
    }

    [Fact]
    public void Жалоба_делится_на_ник_и_причину()
    {
        var command = ChatCommand.Parse("/report Гриферша ломает чужую базу");
        Assert.Equal(ChatCommandKind.Report, command.Kind);
        Assert.True(command.IsValid);
        Assert.Equal("Гриферша", command.Accused);
        Assert.Equal("ломает чужую базу", command.Text);
    }

    [Fact]
    public void Жалоба_без_причины_отклоняется()
    {
        var onlyNick = ChatCommand.Parse("/report Гриферша");
        Assert.Equal(ChatCommandKind.Report, onlyNick.Kind);
        Assert.False(onlyNick.IsValid);

        var shortReason = ChatCommand.Parse("/report Гриферша ой");
        Assert.False(shortReason.IsValid);
    }

    [Fact]
    public void Help_без_текста_это_просьба_объяснить_а_не_пустой_тикет()
    {
        Assert.Equal(ChatCommandKind.Help, ChatCommand.Parse("/help").Kind);
        Assert.Equal(ChatCommandKind.Help, ChatCommand.Parse("/помощь").Kind);
        // А с текстом — уже обращение.
        Assert.Equal(ChatCommandKind.Ticket, ChatCommand.Parse("/help пропали вещи").Kind);
    }
}
