using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Audio2Phone.Streamer;

internal sealed class DiscoveryResponder : IDisposable
{
    public const int DiscoveryPort = 4041;
    private readonly UdpClient _udp = new(DiscoveryPort);
    private readonly int _audioPort;
    private readonly CancellationTokenSource _cancellation = new();
    private readonly Task _loop;

    public DiscoveryResponder(int audioPort)
    {
        _audioPort = audioPort;
        _udp.EnableBroadcast = true;
        _loop = Task.Run(ListenAsync);
    }

    public void Dispose()
    {
        _cancellation.Cancel();
        _udp.Dispose();
        try
        {
            _loop.Wait(TimeSpan.FromSeconds(1));
        }
        catch
        {
        }
        _cancellation.Dispose();
    }

    private async Task ListenAsync()
    {
        while (!_cancellation.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await _udp.ReceiveAsync(_cancellation.Token).ConfigureAwait(false);
            }
            catch
            {
                return;
            }

            var request = Encoding.ASCII.GetString(result.Buffer).Trim();
            if (request != "A2P_DISCOVER")
            {
                continue;
            }

            var name = Environment.MachineName.Replace("|", "");
            var response = Encoding.UTF8.GetBytes($"A2P_HERE|{name}|{_audioPort}");
            await _udp.SendAsync(response, result.RemoteEndPoint, _cancellation.Token).ConfigureAwait(false);
        }
    }
}
