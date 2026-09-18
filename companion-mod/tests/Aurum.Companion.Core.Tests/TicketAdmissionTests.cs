using System;
using System.Collections.Generic;
using Aurum.Companion.Core;
using Aurum.Companion.Core.Game;
using Aurum.Companion.Core.Panel;
using Aurum.Companion.Core.Tickets;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class TicketAdmissionTests
{
    private sealed class Queue : IWorkDispatcher
    {
        public readonly List<Action> Items = new();
        public bool Fail;
        public void Run(Action work) { if (Fail) throw new InvalidOperationException(); Items.Add(work); }
    }

    [Fact]
    public void Ticket_and_report_share_inflight_limit_even_with_zero_cooldown()
    {
        var game = new FakeGameBridge();
        var transport = new FakeTransport();
        var queue = new Queue();
        var service = new TicketService(new PanelClient(CompanionConfig.Parse(Array.Empty<string>()), transport),
            game, new TicketCooldown(0), queue, maxPending: 2);
        var a = new OnlinePlayer(1, "Steam_a", "A");
        var b = new OnlinePlayer(2, "Steam_b", "B");
        var c = new OnlinePlayer(3, "Steam_c", "C");
        service.Handle(a, ChatCommand.Parse("/ticket test"));
        service.Handle(a, ChatCommand.Parse("/report Bob test"));
        service.Handle(b, ChatCommand.Parse("/ticket test"));
        service.Handle(c, ChatCommand.Parse("/ticket test"));
        Assert.Equal(2, queue.Items.Count);
        Assert.Empty(transport.Calls);
        Assert.Contains("ещё отправляется", game.LastMessageTo(a.PlayerId));
        Assert.Contains("занята", game.LastMessageTo(c.PlayerId));
        transport.Respond(503);
        queue.Items[0](); // Failure releases both per-player and global slot.
        service.Handle(a, ChatCommand.Parse("/report Bob test"));
        Assert.Equal(3, queue.Items.Count);
    }

    [Fact]
    public void Dispatcher_failure_does_not_leak_slot()
    {
        var queue = new Queue { Fail = true };
        var service = new TicketService(new PanelClient(CompanionConfig.Parse(Array.Empty<string>()), new FakeTransport()),
            new FakeGameBridge(), new TicketCooldown(0), queue, maxPending: 1);
        var player = new OnlinePlayer(1, "Steam_a", "A");
        service.Handle(player, ChatCommand.Parse("/ticket test"));
        queue.Fail = false;
        service.Handle(player, ChatCommand.Parse("/ticket test"));
        Assert.Single(queue.Items);
    }
}
