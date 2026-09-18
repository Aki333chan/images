using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Aurum.Companion.Core.Http;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public sealed class HttpServerTests
{
    private sealed class Server : IDisposable
    {
        public readonly FakeGameBridge Game = new();
        public readonly CompanionHttpServer Http;
        public readonly int Port;
        public string Url => $"http://127.0.0.1:{Port}";
        public Server(int maxRequests = 4, int maxBytes = 65536, int timeout = 400)
        {
            var socket = new TcpListener(IPAddress.Loopback, 0);
            socket.Start();
            Port = ((IPEndPoint)socket.LocalEndpoint).Port;
            socket.Stop();
            Http = new CompanionHttpServer(new CompanionRouter(Game, "test-token", "test"), Game,
                "127.0.0.1", Port, maxRequests, maxBytes, timeout);
            Http.Start();
        }
        public void Dispose() => Http.Dispose();
        public async Task<TcpClient> Connect(string request)
        {
            var socket = new TcpClient();
            await socket.ConnectAsync(IPAddress.Loopback, Port);
            await socket.GetStream().WriteAsync(Encoding.UTF8.GetBytes(request));
            return socket;
        }
        public async Task<int> Ping()
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
            client.DefaultRequestHeaders.Add("Authorization", "Bearer test-token");
            using var response = await client.GetAsync(Url + "/ping");
            return (int)response.StatusCode;
        }
    }

    private static async Task<string> Status(TcpClient client)
    {
        using var reader = new StreamReader(client.GetStream(), Encoding.UTF8, leaveOpen: true);
        return await reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(3)) ?? "";
    }

    [Fact]
    public async Task Unauthenticated_incomplete_body_is_rejected_without_waiting()
    {
        using var s = new Server(timeout: 2000);
        using var c = await s.Connect("POST /broadcast HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4000\r\n\r\n");
        Assert.Contains("401", await Status(c));
        Assert.Empty(s.Game.Broadcasts);
        Assert.Equal(200, await s.Ping());
    }

    [Fact]
    public async Task Declared_oversize_is_rejected_before_reading()
    {
        using var s = new Server(maxBytes: 32);
        using var c = await s.Connect("POST /broadcast HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer test-token\r\nContent-Length: 100\r\n\r\n");
        Assert.Contains("413", await Status(c));
        Assert.Empty(s.Game.Broadcasts);
    }

    [Fact]
    public async Task Chunked_oversize_is_also_rejected()
    {
        using var s = new Server(maxBytes: 32);
        using var c = await s.Connect("POST /broadcast HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer test-token\r\nTransfer-Encoding: chunked\r\n\r\n40\r\n" + new string('x', 64) + "\r\n0\r\n\r\n");
        Assert.Contains("413", await Status(c));
        Assert.Empty(s.Game.Broadcasts);
    }

    [Fact]
    public async Task Capacity_is_bounded_and_slow_body_expires_without_late_action()
    {
        using var s = new Server(maxRequests: 1, timeout: 800);
        using var c = await s.Connect("POST /broadcast HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer test-token\r\nContent-Length: 20\r\n\r\n{");
        // Observe admission, not an arbitrary sleep before asserting overload.
        int status = 0;
        for (int i = 0; i < 20; i++)
        {
            status = await s.Ping();
            if (status == 503) break;
            await Task.Delay(10);
        }
        Assert.Equal(503, status);
        await Task.Delay(1000);
        Assert.Equal(200, await s.Ping());
        try { await c.GetStream().WriteAsync(Encoding.UTF8.GetBytes("\"text\":\"late\"}")); } catch (IOException) { }
        Assert.Empty(s.Game.Broadcasts);
    }

    [Fact]
    public async Task Valid_utf8_body_reaches_router_and_stop_is_idempotent()
    {
        using var s = new Server();
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
        client.DefaultRequestHeaders.Add("Authorization", "Bearer test-token");
        using var response = await client.PostAsync(s.Url + "/broadcast",
            new StringContent("{\"text\":\"Привет, żółć\"}", Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("Привет, żółć", Assert.Single(s.Game.Broadcasts));
        s.Http.Stop();
        s.Http.Stop();
    }

    [Fact]
    public async Task Invalid_utf8_is_rejected()
    {
        using var s = new Server();
        using var client = new HttpClient();
        client.DefaultRequestHeaders.Add("Authorization", "Bearer test-token");
        using var response = await client.PostAsync(s.Url + "/broadcast", new ByteArrayContent(new byte[] { 0xff }));
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Empty(s.Game.Broadcasts);
    }

    [Fact]
    public async Task Stop_interrupts_pending_body_without_dispatch()
    {
        using var s = new Server(timeout: 10000);
        using var c = await s.Connect("POST /broadcast HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer test-token\r\nContent-Length: 500\r\n\r\n{");
        await Task.Delay(50);
        await Task.Run(s.Http.Stop).WaitAsync(TimeSpan.FromSeconds(2));
        Assert.Empty(s.Game.Broadcasts);
    }
}
