using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using Aurum.Companion.Core.Panel;
using Xunit;

namespace Aurum.Companion.Core.Tests;

public class HttpTransportTests
{
    private static async Task<PanelResponse> Call(string response, int limit = 64, bool holdOpen = false)
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        try
        {
            var call = Task.Run(() => new HttpClientTransport(500, limit).Post($"http://127.0.0.1:{port}/test", "{}", "test-token"));
            using var peer = await listener.AcceptTcpClientAsync().WaitAsync(TimeSpan.FromSeconds(3));
            var stream = peer.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8, leaveOpen: true);
            string? line;
            while (!string.IsNullOrEmpty(line = await reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(3)))) { }
            await stream.WriteAsync(Encoding.UTF8.GetBytes(response));
            if (!holdOpen) peer.Client.Shutdown(SocketShutdown.Send);
            return await call.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally { listener.Stop(); }
    }

    [Fact]
    public async Task Reads_successful_body() =>
        Assert.Equal("{}", (await Call("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")).Body);

    [Fact]
    public async Task Caps_chunked_success_body() =>
        Assert.Equal(0, (await Call("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n80\r\n" + new string('x', 128) + "\r\n0\r\n\r\n")).Status);

    [Fact]
    public async Task Preserves_error_status_when_body_exceeds_limit() =>
        Assert.Equal(403, (await Call("HTTP/1.1 403 Forbidden\r\nContent-Length: 128\r\n\r\n" + new string('x', 128))).Status);

    [Fact]
    public async Task Aborts_stalled_success_body() =>
        Assert.Equal(0, (await Call("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n{", holdOpen: true)).Status);

    [Fact]
    public async Task Stalled_error_body_cannot_escape_exception_handler() =>
        Assert.Equal(403, (await Call("HTTP/1.1 403 Forbidden\r\nContent-Length: 20\r\n\r\n{", holdOpen: true)).Status);
}
