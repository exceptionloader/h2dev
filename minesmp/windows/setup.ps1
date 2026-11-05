# =====================================================================
#  MineSMP - Windows-Installer und Starter
#  Wird von "MineSMP starten.bat" aufgerufen. Beim ersten Mal:
#  Java 25 pruefen/installieren, Paper + Plugins laden, EULA, Admin-Rechte, Raenge.
#  Danach: startet einfach den Server.
# =====================================================================
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Host.UI.RawUI.WindowTitle = 'MineSMP Server'

$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$Server  = Join-Path $Root 'server'
$Plugins = Join-Path $Server 'plugins'
$McVersion = '1.21.11'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Info($t)  { Write-Host "  $t" -ForegroundColor Cyan }
function Ok($t)    { Write-Host "  OK  $t" -ForegroundColor Green }
function Warn($t)  { Write-Host "  !!  $t" -ForegroundColor Yellow }
function Fail($t)  { Write-Host ""; Write-Host "  FEHLER: $t" -ForegroundColor Red; Write-Host ""; Read-Host '  Enter zum Beenden'; exit 1 }
function Write-NoBom($path, $text) { [IO.File]::WriteAllText($path, $text, $Utf8) }

Write-Host ''
Write-Host '  MINE' -ForegroundColor Cyan -NoNewline; Write-Host 'SMP' -ForegroundColor White -NoNewline; Write-Host '  - lokaler Testserver' -ForegroundColor DarkGray
Write-Host ''

# ---------------------------------------------------------------- Java 25
function Get-JavaMajor($exe) {
    try {
        $out = & $exe -version 2>&1 | Out-String
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}
function Find-Java {
    $candidates = @()
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft")) {
        if (Test-Path $base) {
            Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object {
                $j = Join-Path $_.FullName 'bin\java.exe'
                if (Test-Path $j) { $candidates += $j }
            }
        }
    }
    foreach ($c in $candidates) { if ((Get-JavaMajor $c) -ge 25) { return $c } }
    return $null
}

$Java = Find-Java
if (-not $Java) {
    Warn 'Java 25 wurde nicht gefunden (wird fuer den Server gebraucht).'
    $a = Read-Host '  Soll Java 25 (Eclipse Temurin) jetzt installiert werden? [J/N]'
    if ($a -notmatch '^[JjYy]') { Fail 'Ohne Java 25 kann der Server nicht starten. Download: https://adoptium.net' }
    Info 'Lade Java 25 herunter ...'
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $msiUrl = ($assets | Where-Object { $_.binary.installer } | Select-Object -First 1).binary.installer.link
    if (-not $msiUrl) { Fail 'Java-Download nicht gefunden. Bitte manuell von https://adoptium.net installieren.' }
    $msi = Join-Path $env:TEMP 'temurin25.msi'
    Invoke-WebRequest $msiUrl -OutFile $msi -UseBasicParsing
    Info 'Installiere Java 25 (Windows fragt evtl. nach Administrator-Rechten) ...'
    $p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /passive ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJavaHome" -Verb RunAs -Wait -PassThru
    if ($p.ExitCode -ne 0) { Fail "Java-Installation fehlgeschlagen (Code $($p.ExitCode))." }
    $Java = Find-Java
    if (-not $Java) { Fail 'Java 25 wurde installiert, aber nicht gefunden. Bitte das Fenster schliessen und erneut starten.' }
}
Ok "Java: $Java"

# ---------------------------------------------------------------- Dateien
New-Item -ItemType Directory -Force -Path $Plugins, (Join-Path $Server 'config'), (Join-Path $Plugins 'LuckPerms') | Out-Null

$paperJar = Join-Path $Server 'paper.jar'
if (-not (Test-Path $paperJar)) {
    Info "Lade Paper $McVersion ..."
    $build = Invoke-RestMethod "https://fill.papermc.io/v3/projects/paper/versions/$McVersion/builds/latest"
    Invoke-WebRequest $build.downloads.'server:default'.url -OutFile $paperJar -UseBasicParsing
    Ok "Paper $McVersion (Build $($build.id))"
}

function Get-ModrinthPlugin($slug, $file) {
    $target = Join-Path $Plugins $file
    if (Test-Path $target) { return }
    $url = "https://api.modrinth.com/v2/project/$slug/version?game_versions=%5B%22$McVersion%22%5D&loaders=%5B%22paper%22%5D"
    try {
        $versions = Invoke-RestMethod $url -Headers @{ 'User-Agent' = 'MineSMP-Installer/1.0' }
        if (-not $versions -or $versions.Count -eq 0) { Warn "$slug hat keine Version fuer $McVersion - uebersprungen."; return }
        $f = $versions[0].files | Where-Object { $_.primary } | Select-Object -First 1
        if (-not $f) { $f = $versions[0].files[0] }
        Invoke-WebRequest $f.url -OutFile $target -UseBasicParsing
        Ok "$file ($($versions[0].version_number))"
    } catch { Warn "$slug konnte nicht geladen werden: $($_.Exception.Message)" }
}

Info 'Lade Plugins ...'
Get-ModrinthPlugin 'luckperms' 'LuckPerms.jar'
Get-ModrinthPlugin 'placeholderapi' 'PlaceholderAPI.jar'
Get-ModrinthPlugin 'worldedit' 'WorldEdit.jar'
Get-ModrinthPlugin 'worldguard' 'WorldGuard.jar'
Get-ModrinthPlugin 'coreprotect' 'CoreProtect.jar'
Get-ModrinthPlugin 'gsit' 'GSit.jar'
$vault = Join-Path $Plugins 'Vault.jar'
if (-not (Test-Path $vault)) {
    Invoke-WebRequest 'https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar' -OutFile $vault -UseBasicParsing
    Ok 'Vault.jar'
}
Copy-Item (Join-Path $Root 'files\MineSMPCore-1.0.0.jar') $Plugins -Force
Ok 'MineSMPCore-1.0.0.jar'

foreach ($f in @('server.properties', 'bukkit.yml', 'spigot.yml')) {
    $t = Join-Path $Server $f
    if (-not (Test-Path $t)) { Copy-Item (Join-Path $Root "files\$f") $t }
}
foreach ($f in @('paper-global.yml', 'paper-world-defaults.yml')) {
    $t = Join-Path $Server "config\$f"
    if (-not (Test-Path $t)) { Copy-Item (Join-Path $Root "files\$f") $t }
}

# ---------------------------------------------------------------- Erststart-Fragen
$firstRun = -not (Test-Path (Join-Path $Server '.minesmp-setup-done'))
$eula = Join-Path $Server 'eula.txt'
if (-not ((Test-Path $eula) -and ((Get-Content $eula -Raw) -match 'eula=true'))) {
    Write-Host ''
    Info 'Minecraft-Server brauchen die Zustimmung zur Minecraft-EULA: https://aka.ms/MinecraftEULA'
    $a = Read-Host '  Stimmst du der EULA zu? [J/N]'
    if ($a -notmatch '^[JjYy]') { Fail 'Ohne Zustimmung zur EULA startet der Server nicht.' }
    Write-NoBom $eula "eula=true`n"
}

$ownerName = $null
if ($firstRun) {
    Write-Host ''
    $ownerName = Read-Host '  Dein Minecraft-Name (bekommt Owner- und Admin-Rechte)'
    $ownerName = $ownerName.Trim()
    if ($ownerName -match '^[A-Za-z0-9_]{3,16}$') {
        try {
            $prof = Invoke-RestMethod "https://api.mojang.com/users/profiles/minecraft/$ownerName"
            $id = $prof.id
            $uuid = $id.Substring(0,8) + '-' + $id.Substring(8,4) + '-' + $id.Substring(12,4) + '-' + $id.Substring(16,4) + '-' + $id.Substring(20)
            Write-NoBom (Join-Path $Server 'ops.json') ("[{`"uuid`":`"$uuid`",`"name`":`"$($prof.name)`",`"level`":4,`"bypassesPlayerLimit`":false}]")
            $ownerName = $prof.name
            Ok "$ownerName ist Operator."
        } catch { Warn "Minecraft-Name nicht gefunden. Du kannst spaeter im Fenster 'op DeinName' eingeben."; $ownerName = $null }
    } else { Warn 'Ungueltiger Name - uebersprungen.'; $ownerName = $null }
    Copy-Item (Join-Path $Root 'files\minesmp-raenge.json.gz') (Join-Path $Plugins 'LuckPerms') -Force
}

# ---------------------------------------------------------------- Start
$ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
$mem = if ($ramGb -ge 12) { '4G' } elseif ($ramGb -ge 8) { '3G' } else { '2G' }
$log = Join-Path $Server 'logs\latest.log'
if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }

Write-Host ''
Info "Starte Server mit $mem RAM ... (dauert beim ersten Mal 1-2 Minuten)"
Write-Host ''
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Java
$psi.Arguments = "-Xms$mem -Xmx$mem -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar paper.jar --nogui"
$psi.WorkingDirectory = $Server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$proc = [System.Diagnostics.Process]::Start($psi)

# Warten bis der Server bereit ist
$ready = $false
for ($i = 0; $i -lt 600 -and -not $proc.HasExited; $i++) {
    Start-Sleep -Seconds 1
    if ((Test-Path $log) -and ((Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'Done \(')) { $ready = $true; break }
}
if (-not $ready) { if ($proc.HasExited) { Fail 'Der Server hat sich beendet. Siehe Meldungen oben.' } }

if ($firstRun) {
    $proc.StandardInput.WriteLine('lp import minesmp-raenge')
    Start-Sleep -Seconds 6
    if ($ownerName) { $proc.StandardInput.WriteLine("lp user $ownerName parent set owner") }
    Write-NoBom (Join-Path $Server '.minesmp-setup-done') (Get-Date).ToString('s')
}

Write-Host ''
Write-Host '  ======================================================' -ForegroundColor Cyan
Write-Host '   Server laeuft!  Minecraft Java 1.21.11 starten:'        -ForegroundColor Cyan
Write-Host '   Mehrspieler -> Direktverbindung -> localhost'            -ForegroundColor White
Write-Host '   Befehle hier eintippen (z.B. op Name). Beenden: stop'    -ForegroundColor Cyan
Write-Host '  ======================================================' -ForegroundColor Cyan
Write-Host ''

while (-not $proc.HasExited) {
    $line = Read-Host
    if ($proc.HasExited) { break }
    if ($line) { $proc.StandardInput.WriteLine($line) }
    if ($line -eq 'stop') { $proc.WaitForExit(60000) | Out-Null }
}
Write-Host ''
Ok 'Server gestoppt. Neu starten: "MineSMP starten.bat" doppelklicken.'
Read-Host '  Enter zum Schliessen'
