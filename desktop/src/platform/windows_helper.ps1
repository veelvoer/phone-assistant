# Persistent helper: reads one JSON command per line, answers one JSON line.
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices; using System.Text;
public class W32 {
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, int d, UIntPtr e);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  public static void TypeText(string s) {
    foreach (char c in s) {
      INPUT[] a = new INPUT[2];
      a[0].type = 1; a[0].u.ki.wScan = c; a[0].u.ki.dwFlags = 0x4;
      a[1].type = 1; a[1].u.ki.wScan = c; a[1].u.ki.dwFlags = 0x4 | 0x2;
      SendInput(2, a, Marshal.SizeOf(typeof(INPUT)));
      System.Threading.Thread.Sleep(4);
    }
  }
}
"@
[void][W32]::SetProcessDPIAware()
$utf8 = New-Object System.Text.UTF8Encoding($false); [Console]::OutputEncoding = $utf8; [Console]::InputEncoding = $utf8
function Out-Json($o) { [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress -Depth 4)); [Console]::Out.Flush() }
function Btn($b) { switch ($b) { 'right' { return @(0x8, 0x10) } 'middle' { return @(0x20, 0x40) } default { return @(0x2, 0x4) } } }
Out-Json @{ ready = $true }
while ($true) {
  $line = [Console]::In.ReadLine(); if ($line -eq $null) { break }; if ($line.Trim() -eq '') { continue }
  try {
    $m = $line | ConvertFrom-Json
    switch ($m.op) {
      'move'   { [void][W32]::SetCursorPos([int]$m.x, [int]$m.y); Out-Json @{ ok = $true } }
      'click'  { [void][W32]::SetCursorPos([int]$m.x, [int]$m.y); Start-Sleep -Milliseconds 30; $b = Btn $m.button; for ($i = 0; $i -lt [int]$m.count; $i++) { [W32]::mouse_event($b[0], 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 40; [W32]::mouse_event($b[1], 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70 }; Out-Json @{ ok = $true } }
      'btn'    { $b = Btn $m.button; if ($m.down) { [W32]::mouse_event($b[0], 0, 0, 0, [UIntPtr]::Zero) } else { [W32]::mouse_event($b[1], 0, 0, 0, [UIntPtr]::Zero) }; Out-Json @{ ok = $true } }
      'wheel'  { if ($m.v) { [W32]::mouse_event(0x800, 0, 0, [int]$m.v * 120, [UIntPtr]::Zero) }; if ($m.h) { [W32]::mouse_event(0x1000, 0, 0, [int]$m.h * 120, [UIntPtr]::Zero) }; Out-Json @{ ok = $true } }
      'type'   { [W32]::TypeText([string]$m.text); Out-Json @{ ok = $true } }
      'keys'   { foreach ($v in $m.vks) { [W32]::keybd_event([byte]$v, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 8 }; Start-Sleep -Milliseconds 30; foreach ($v in ($m.vks | Sort-Object -Descending { [array]::IndexOf($m.vks, $_) })) { [W32]::keybd_event([byte]$v, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 8 }; Out-Json @{ ok = $true } }
      'active' { $h = [W32]::GetForegroundWindow(); $sb = New-Object System.Text.StringBuilder 512; [void][W32]::GetWindowText($h, $sb, 512); $pid2 = 0; [void][W32]::GetWindowThreadProcessId($h, [ref]$pid2); $p = Get-Process -Id $pid2 -ErrorAction SilentlyContinue; Out-Json @{ title = $sb.ToString(); class = $(if ($p) { $p.ProcessName } else { '' }) } }
      'windows' { $l = @(Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { @{ class = $_.ProcessName; title = $_.MainWindowTitle } }); Out-Json @{ list = $l } }
      'focus'  { $p = Get-Process | Where-Object { $_.MainWindowTitle -and ($_.MainWindowTitle -like "*$($m.q)*" -or $_.ProcessName -like "*$($m.q)*") } | Select-Object -First 1; if ($p) { [void][W32]::ShowWindow($p.MainWindowHandle, 9); [void][W32]::SetForegroundWindow($p.MainWindowHandle); Out-Json @{ ok = $true } } else { Out-Json @{ ok = $false } } }
      'app'    { $a = Get-StartApps | Where-Object { $_.Name -like "*$($m.name)*" } | Select-Object -First 1; if ($a) { Start-Process "shell:AppsFolder\$($a.AppID)"; Out-Json @{ ok = $true; name = $a.Name } } else { try { Start-Process $m.name; Out-Json @{ ok = $true; name = $m.name } } catch { Out-Json @{ ok = $false } } } }
      default  { Out-Json @{ error = "unknown op $($m.op)" } }
    }
  } catch { Out-Json @{ error = $_.Exception.Message } }
}
