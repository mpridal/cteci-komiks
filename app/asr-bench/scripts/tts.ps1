# Vygeneruje WAV z textu systémovým hlasem Windows (OneCore, i český Jakub).
# Použití: powershell -File tts.ps1 -Voice Jakub -Text "TO JE VIKY." -Out out.wav [-Rate 0.8] [-Pitch 1.6]
param([string]$Voice = "Jakub", [string]$Text, [string]$Out, [double]$Rate = 1.0, [double]$Pitch = 1.0)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(); $task.Result }
$s = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer
$v = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { $_.DisplayName -like "*$Voice*" } | Select-Object -First 1
if (-not $v) { throw "Hlas $Voice nenalezen" }
$s.Voice = $v
$s.Options.SpeakingRate = $Rate
$s.Options.AudioPitch = $Pitch   # 0–2, 1 = výchozí; vyšší ≈ dětský hlas
$stream = Await ($s.SynthesizeTextToStreamAsync($Text)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])
$reader = New-Object Windows.Storage.Streams.DataReader($stream.GetInputStreamAt(0))
$n = [uint32]$stream.Size
$null = Await ($reader.LoadAsync($n)) ([uint32])
$bytes = New-Object byte[] $n
$reader.ReadBytes($bytes)
[System.IO.File]::WriteAllBytes($Out, $bytes)
