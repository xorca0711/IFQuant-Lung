$ErrorActionPreference = "Continue"
$repo = "X:\GitHub\IFQuant-Lung"
$data = "D:\Confocal_Images\260808-CW\260808-CW"
$out  = "D:\IFQ_Runs\confocal_260808"
$fj   = "X:\Fiji"
$jre  = (Get-ChildItem "$fj\java" -Recurse -Filter java.exe | Select-Object -First 1).FullName

# the engine requires samplesheet.csv to sit in INPUT_DIR
Copy-Item "$out\samplesheet.csv" "$data\samplesheet.csv" -Force

$env:IFQ_INPUT_DIR      = $data
$env:IFQ_OUTPUT_DIR     = "$out\analysis"
$env:IFQ_RECURSIVE      = "true"
# IFQ_INCLUDE_REGEX is a FULL match against the ABSOLUTE PATH. Without it,
# recursive discovery also picks up the 288 4x navigation fields, which are not
# analysis images and have no panel-map row -- the engine then fail-closes.
$env:IFQ_INCLUDE_REGEX  = ".*20x 2k.*\.oir"
$env:IFQ_PANEL_MAP_PATH = "$out\panel_map.csv"   # authoritative; every image needs a row
$env:IFQ_MARKER_REGISTRY = "$repo\config\lung_marker_registry.json"
$env:IFQ_SEGMENTER      = "classic"
$env:IFQ_PROJECTION     = "max"                   # SizeZ=1, so no projection actually runs
$env:IFQ_MIN_INCLUDED_NUCLEI = "0"                # or sparse fields vanish, losing their area

# KRT5 frozen from the UNINFECTED CONTROLS ONLY.
# Pooled in-tissue p99.99 was 283 (M4-2) and 255 (M6); 300 sits just above both,
# giving a false-positive area fraction <= 1e-4 in each control independently.
# Infected M2 has 8.1% of tissue above 500, so the separation is 3+ orders.
$env:IFQ_KRT5_THRESHOLD = "300"

# AGER and T1A are deliberately LEFT ADAPTIVE. They are constitutively expressed,
# so "the control should be negative" gives no calibration handle for them. The
# engine will mark their calls exploratory_* which is the honest label.

& $jre '--add-opens=java.base/java.lang=ALL-UNNAMED' `
  "-javaagent:$fj\jars\ij1-patcher-2.0.0.jar=init" `
  '-Djava.awt.headless=true' "-Dplugins.dir=$fj" '-Xmx4g' `
  -cp "$fj\jars\*;$fj\plugins\*" net.imagej.Main --headless `
  --run "$repo\IF_Quant_Pipeline.groovy" 2>&1 |
  Out-File "$out\engine.log" -Encoding utf8

"exit=$LASTEXITCODE"
