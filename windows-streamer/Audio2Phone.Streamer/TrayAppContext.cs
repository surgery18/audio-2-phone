using System.Drawing;
using System.Windows.Forms;

namespace Audio2Phone.Streamer;

internal sealed class TrayAppContext : ApplicationContext
{
    private readonly StreamerService _service = new(4040);
    private readonly NotifyIcon _notifyIcon;
    private readonly ToolStripMenuItem _startStopItem;
    private readonly ToolStripMenuItem _statusItem;

    public TrayAppContext()
    {
        _startStopItem = new ToolStripMenuItem("Start streaming", null, (_, _) => ToggleStreaming());
        _statusItem = new ToolStripMenuItem("Stopped") { Enabled = false };

        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_startStopItem);
        menu.Items.Add(new ToolStripMenuItem("Show network info", null, (_, _) => ShowNetworkInfo()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, async (_, _) => await ExitAsync()));

        _notifyIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Audio 2 Phone",
            Visible = true,
            ContextMenuStrip = menu
        };
        _notifyIcon.DoubleClick += (_, _) => ShowNetworkInfo();

        _service.PairingRequested += ConfirmPairingAsync;
        _service.StatusChanged += (_, status) =>
        {
            if (Application.OpenForms.Count > 0)
            {
                Application.OpenForms[0]?.BeginInvoke(() => ApplyStatus(status));
            }
            else
            {
                ApplyStatus(status);
            }
        };
        _service.Start();
        ShowNetworkInfo();
    }

    private void ToggleStreaming()
    {
        if (_service.IsStreaming)
        {
            _service.Stop();
        }
        else
        {
            _service.Start();
        }

        RefreshMenu();
    }

    private void ApplyStatus(string status)
    {
        _statusItem.Text = status;
        _notifyIcon.Text = status.Length > 48 ? "Audio 2 Phone" : $"Audio 2 Phone - {status}";
        RefreshMenu();
    }

    private void RefreshMenu()
    {
        _startStopItem.Text = _service.IsStreaming ? "Stop streaming" : "Start streaming";
    }

    private async Task ExitAsync()
    {
        _notifyIcon.Visible = false;
        await _service.DisposeAsync();
        _notifyIcon.Dispose();
        ExitThread();
    }

    private void ShowNetworkInfo()
    {
        var addresses = string.Join(Environment.NewLine, _service.LanAddresses);
        MessageBox.Show(
            $"Audio streaming port: {_service.Port}{Environment.NewLine}Discovery port: {DiscoveryResponder.DiscoveryPort}{Environment.NewLine}{Environment.NewLine}PC addresses:{Environment.NewLine}{addresses}{Environment.NewLine}{Environment.NewLine}Open the Android app and tap Find computers.",
            "Audio 2 Phone",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private Task<bool> ConfirmPairingAsync(string code, System.Net.EndPoint? remoteEndPoint)
    {
        var result = MessageBox.Show(
            $"A phone wants to pair with this computer.{Environment.NewLine}{Environment.NewLine}Code: {code}{Environment.NewLine}Device: {remoteEndPoint}{Environment.NewLine}{Environment.NewLine}Does this code match the one shown on your phone?",
            "Audio 2 Phone Pairing",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Question,
            MessageBoxDefaultButton.Button2);

        return Task.FromResult(result == DialogResult.Yes);
    }
}
