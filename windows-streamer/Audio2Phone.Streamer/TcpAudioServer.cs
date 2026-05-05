using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace Audio2Phone.Streamer;

internal sealed class TcpAudioServer : IAsyncDisposable
{
    private readonly TcpListener _listener;
    private readonly SessionStore _sessions = new();
    private readonly object _gate = new();
    private TcpClient? _client;
    private NetworkStream? _stream;
    private AudioFormat? _format;

    public TcpAudioServer(int port)
    {
        _listener = new TcpListener(IPAddress.Any, port);
    }

    public event Func<string, EndPoint?, Task<bool>>? PairingRequested;

    public void Start()
    {
        _listener.Start();
        _ = AcceptLoopAsync();
    }

    public async ValueTask DisposeAsync()
    {
        _listener.Stop();
        await DropClientAsync();
    }

    public async Task SendFormatAsync(AudioFormat format, CancellationToken cancellationToken)
    {
        _format = format;
        var stream = GetStream();
        if (stream is null)
        {
            return;
        }

        await WriteFormatAsync(stream, format, cancellationToken);
    }

    public async Task SendAudioAsync(ReadOnlyMemory<byte> pcm, CancellationToken cancellationToken)
    {
        var stream = GetStream();
        if (stream is null)
        {
            return;
        }

        try
        {
            await stream.WriteAsync(pcm, cancellationToken);
        }
        catch (IOException)
        {
            await DropClientAsync();
        }
        catch (ObjectDisposedException)
        {
            await DropClientAsync();
        }
    }

    public static IEnumerable<string> GetLanAddresses()
    {
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }

            foreach (var address in adapter.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address.Address))
                {
                    yield return address.Address.ToString();
                }
            }
        }
    }

    private async Task WriteFormatAsync(NetworkStream stream, AudioFormat format, CancellationToken cancellationToken)
    {
        var header = new byte[20];
        Encoding.ASCII.GetBytes("A2P1").CopyTo(header, 0);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(4), format.SampleRate);
        BinaryPrimitives.WriteInt16LittleEndian(header.AsSpan(8), (short)format.OutputChannels);
        BinaryPrimitives.WriteInt16LittleEndian(header.AsSpan(10), 16);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(12), Environment.TickCount);

        try
        {
            await stream.WriteAsync(header, cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }
        catch (IOException)
        {
            await DropClientAsync();
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (true)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync();
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                return;
            }

            _ = AuthenticateClientAsync(client);
        }
    }

    private async Task AuthenticateClientAsync(TcpClient client)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));

        try
        {
            client.NoDelay = true;
            var stream = client.GetStream();
            var reader = new StreamReader(stream, Encoding.ASCII, false, leaveOpen: true);
            var line = await reader.ReadLineAsync(timeout.Token);

            if (line is null)
            {
                client.Dispose();
                return;
            }

            string token;
            if (line.StartsWith("RESUME ", StringComparison.Ordinal))
            {
                token = line[7..].Trim();
                if (!_sessions.IsTrusted(token))
                {
                    await WriteLineAsync(stream, "ERR Untrusted session", timeout.Token);
                    client.Dispose();
                    return;
                }
            }
            else if (line.StartsWith("PAIR ", StringComparison.Ordinal))
            {
                var code = line[5..].Trim();
                if (code.Length != 6 || code.Any(ch => !char.IsDigit(ch)))
                {
                    await WriteLineAsync(stream, "ERR Invalid code", timeout.Token);
                    client.Dispose();
                    return;
                }

                var approved = PairingRequested is not null
                    && await PairingRequested.Invoke(code, client.Client.RemoteEndPoint).ConfigureAwait(false);
                if (!approved)
                {
                    await WriteLineAsync(stream, "ERR Rejected", timeout.Token);
                    client.Dispose();
                    return;
                }

                token = _sessions.Create();
            }
            else
            {
                client.Dispose();
                return;
            }

            await WriteLineAsync(stream, "OK " + token, timeout.Token);

            if (_format is not null)
            {
                await WriteFormatAsync(stream, _format, timeout.Token);
            }

            lock (_gate)
            {
                _stream?.Dispose();
                _client?.Dispose();
                _client = client;
                _stream = stream;
            }

        }
        catch
        {
            client.Dispose();
        }
    }

    private static Task WriteLineAsync(NetworkStream stream, string line, CancellationToken cancellationToken)
    {
        var bytes = Encoding.ASCII.GetBytes(line + "\n");
        return stream.WriteAsync(bytes, cancellationToken).AsTask();
    }

    private NetworkStream? GetStream()
    {
        lock (_gate)
        {
            return _stream;
        }
    }

    private Task DropClientAsync()
    {
        lock (_gate)
        {
            _stream?.Dispose();
            _client?.Dispose();
            _stream = null;
            _client = null;
        }

        return Task.CompletedTask;
    }
}
