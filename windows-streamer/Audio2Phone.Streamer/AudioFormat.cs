namespace Audio2Phone.Streamer;

internal sealed record AudioFormat(
    int SampleRate,
    int SourceChannels,
    int OutputChannels,
    int BitsPerSample,
    int BlockAlign,
    bool IsFloat);
