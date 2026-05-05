using System.Runtime.InteropServices;

namespace Audio2Phone.Streamer;

internal sealed class WasapiLoopbackCapture : IDisposable
{
    private const int ClsCtxAll = 23;
    private const int AudclntSharemodeShared = 0;
    private const int AudclntStreamflagsLoopback = 0x00020000;
    private static readonly Guid IAudioClientGuid = new("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static readonly Guid IAudioCaptureClientGuid = new("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    private static readonly Guid PcmGuid = new("00000001-0000-0010-8000-00AA00389B71");
    private static readonly Guid FloatGuid = new("00000003-0000-0010-8000-00AA00389B71");

    private readonly IAudioClient _audioClient;
    private readonly IAudioCaptureClient _captureClient;
    private readonly IMMDevice _device;
    private readonly object _enumerator;
    private bool _started;

    public WasapiLoopbackCapture()
    {
        _enumerator = new MMDeviceEnumerator();
        var deviceEnumerator = (IMMDeviceEnumerator)_enumerator;
        Marshal.ThrowExceptionForHR(deviceEnumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eConsole, out _device));

        var audioClientGuid = IAudioClientGuid;
        Marshal.ThrowExceptionForHR(_device.Activate(ref audioClientGuid, ClsCtxAll, IntPtr.Zero, out var audioClientObject));
        _audioClient = (IAudioClient)audioClientObject;

        Marshal.ThrowExceptionForHR(_audioClient.GetMixFormat(out var formatPointer));
        try
        {
            Format = ParseFormat(formatPointer);

            Marshal.ThrowExceptionForHR(_audioClient.Initialize(
                AudclntSharemodeShared,
                AudclntStreamflagsLoopback,
                10_000_000,
                0,
                formatPointer,
                IntPtr.Zero));
        }
        finally
        {
            Marshal.FreeCoTaskMem(formatPointer);
        }

        var captureClientGuid = IAudioCaptureClientGuid;
        Marshal.ThrowExceptionForHR(_audioClient.GetService(ref captureClientGuid, out var captureClientObject));
        _captureClient = (IAudioCaptureClient)captureClientObject;
    }

    public AudioFormat Format { get; }

    public void Start()
    {
        if (_started)
        {
            return;
        }

        Marshal.ThrowExceptionForHR(_audioClient.Start());
        _started = true;
    }

    public void Stop()
    {
        if (!_started)
        {
            return;
        }

        _audioClient.Stop();
        _started = false;
    }

    public unsafe int Read(Span<byte> destination)
    {
        Marshal.ThrowExceptionForHR(_captureClient.GetNextPacketSize(out var packetFrames));
        if (packetFrames == 0)
        {
            return 0;
        }

        Marshal.ThrowExceptionForHR(_captureClient.GetBuffer(
            out var data,
            out var frameCount,
            out var flags,
            out _,
            out _));

        try
        {
            if ((flags & AudioClientBufferFlags.Silent) != 0)
            {
                destination.Slice(0, frameCount * Format.OutputChannels * 2).Clear();
                return frameCount * Format.OutputChannels * 2;
            }

            var sourceBytes = frameCount * Format.BlockAlign;
            var source = new ReadOnlySpan<byte>(data.ToPointer(), sourceBytes);
            return Pcm16Converter.Convert(source, frameCount, Format, destination);
        }
        finally
        {
            _captureClient.ReleaseBuffer(frameCount);
        }
    }

    public void Dispose()
    {
        Stop();
        Marshal.ReleaseComObject(_captureClient);
        Marshal.ReleaseComObject(_audioClient);
        Marshal.ReleaseComObject(_device);
        Marshal.ReleaseComObject(_enumerator);
    }

    private static AudioFormat ParseFormat(IntPtr pointer)
    {
        var format = Marshal.PtrToStructure<WaveFormatEx>(pointer);
        var isFloat = format.FormatTag == 3;
        var bitsPerSample = (int)format.BitsPerSample;

        if (format.FormatTag == 0xfffe)
        {
            var validBits = Marshal.ReadInt16(pointer, 18);
            var subFormat = Marshal.PtrToStructure<Guid>(IntPtr.Add(pointer, 24));
            isFloat = subFormat == FloatGuid;
            bitsPerSample = validBits > 0 ? validBits : format.BitsPerSample;

            if (subFormat != PcmGuid && subFormat != FloatGuid)
            {
                throw new NotSupportedException($"Unsupported WASAPI subformat: {subFormat}");
            }
        }

        var outputChannels = Math.Clamp((int)format.Channels, 1, 2);

        return new AudioFormat(
            format.SamplesPerSec,
            format.Channels,
            outputChannels,
            bitsPerSample,
            format.BlockAlign,
            isFloat);
    }

    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private sealed class MMDeviceEnumerator
    {
    }

    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
    }

    [ComImport]
    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr audioSessionGuid);
        int GetBufferSize(out uint bufferSize);
        int GetStreamLatency(out long latency);
        int GetCurrentPadding(out uint currentPadding);
        int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closestMatch);
        int GetMixFormat(out IntPtr deviceFormat);
        int GetDevicePeriod(out long defaultDevicePeriod, out long minimumDevicePeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr eventHandle);
        int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport]
    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        int GetBuffer(out IntPtr data, out int framesToRead, out AudioClientBufferFlags flags, out long devicePosition, out long qpcPosition);
        int ReleaseBuffer(int framesRead);
        int GetNextPacketSize(out int packetSize);
    }

    private enum EDataFlow
    {
        eRender,
        eCapture,
        eAll
    }

    private enum ERole
    {
        eConsole,
        eMultimedia,
        eCommunications
    }

    [Flags]
    private enum AudioClientBufferFlags
    {
        None = 0,
        DataDiscontinuity = 1,
        Silent = 2,
        TimestampError = 4
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WaveFormatEx
    {
        public ushort FormatTag;
        public ushort Channels;
        public int SamplesPerSec;
        public int AvgBytesPerSec;
        public ushort BlockAlign;
        public ushort BitsPerSample;
        public ushort Size;
    }
}
