using System.Buffers.Binary;

namespace Audio2Phone.Streamer;

internal static class Pcm16Converter
{
    public static int Convert(ReadOnlySpan<byte> source, int frames, AudioFormat format, Span<byte> destination)
    {
        var bytesPerInputSample = format.BitsPerSample / 8;
        var written = 0;

        for (var frame = 0; frame < frames; frame++)
        {
            var frameOffset = frame * format.BlockAlign;

            for (var channel = 0; channel < format.OutputChannels; channel++)
            {
                var inputChannel = Math.Min(channel, format.SourceChannels - 1);
                var sampleOffset = frameOffset + inputChannel * bytesPerInputSample;
                var sample = ReadSample(source.Slice(sampleOffset, bytesPerInputSample), format);
                BinaryPrimitives.WriteInt16LittleEndian(destination.Slice(written, 2), sample);
                written += 2;
            }
        }

        return written;
    }

    private static short ReadSample(ReadOnlySpan<byte> source, AudioFormat format)
    {
        if (format.IsFloat)
        {
            var value = BitConverter.ToSingle(source);
            value = Math.Clamp(value, -1.0f, 1.0f);
            return (short)Math.Round(value * short.MaxValue);
        }

        return format.BitsPerSample switch
        {
            16 => BinaryPrimitives.ReadInt16LittleEndian(source),
            24 => (short)(ReadInt24(source) >> 8),
            32 => (short)(BinaryPrimitives.ReadInt32LittleEndian(source) >> 16),
            _ => throw new NotSupportedException($"Unsupported PCM depth: {format.BitsPerSample}")
        };
    }

    private static int ReadInt24(ReadOnlySpan<byte> source)
    {
        var value = source[0] | (source[1] << 8) | (source[2] << 16);
        return (value & 0x800000) != 0 ? value | unchecked((int)0xff000000) : value;
    }
}
