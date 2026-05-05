namespace Audio2Phone.Streamer;

internal sealed class StreamerService : IAsyncDisposable
{
    private readonly TcpAudioServer _server;
    private readonly DiscoveryResponder _discovery;
    private WasapiLoopbackCapture? _capture;
    private CancellationTokenSource? _cancellation;
    private Task? _streamTask;

    public StreamerService(int port)
    {
        Port = port;
        _server = new TcpAudioServer(port);
        _discovery = new DiscoveryResponder(port);
        _server.Start();
    }

    public int Port { get; }
    public bool IsStreaming => _streamTask is { IsCompleted: false };
    public AudioFormat? Format { get; private set; }

    public IReadOnlyList<string> LanAddresses => TcpAudioServer.GetLanAddresses().ToArray();

    public event EventHandler<string>? StatusChanged;
    public event Func<string, System.Net.EndPoint?, Task<bool>>?
        PairingRequested
        {
            add => _server.PairingRequested += value;
            remove => _server.PairingRequested -= value;
        }

    public void Start()
    {
        if (IsStreaming)
        {
            return;
        }

        _cancellation = new CancellationTokenSource();
        _streamTask = Task.Run(() => StreamLoopAsync(_cancellation.Token));
        StatusChanged?.Invoke(this, "Starting");
    }

    public void Stop()
    {
        if (!IsStreaming)
        {
            return;
        }

        _cancellation?.Cancel();
        StatusChanged?.Invoke(this, "Stopped");
    }

    public async ValueTask DisposeAsync()
    {
        Stop();

        if (_streamTask is not null)
        {
            try
            {
                await _streamTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        await _server.DisposeAsync().ConfigureAwait(false);
        _discovery.Dispose();
        _cancellation?.Dispose();
    }

    private async Task StreamLoopAsync(CancellationToken cancellationToken)
    {
        try
        {
            using var capture = new WasapiLoopbackCapture();
            _capture = capture;
            Format = capture.Format;
            await _server.SendFormatAsync(capture.Format, cancellationToken).ConfigureAwait(false);

            var maxFramesPerRead = capture.Format.SampleRate / 10;
            var buffer = new byte[maxFramesPerRead * capture.Format.OutputChannels * 2];

            capture.Start();
            StatusChanged?.Invoke(this, $"Streaming {capture.Format.SampleRate} Hz");

            while (!cancellationToken.IsCancellationRequested)
            {
                var bytes = capture.Read(buffer);
                if (bytes > 0)
                {
                    await _server.SendAudioAsync(buffer.AsMemory(0, bytes), cancellationToken).ConfigureAwait(false);
                }
                else
                {
                    await Task.Delay(5, cancellationToken).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke(this, $"Error: {ex.Message}");
        }
        finally
        {
            _capture = null;
            StatusChanged?.Invoke(this, "Stopped");
        }
    }
}
