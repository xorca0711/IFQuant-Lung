param(
    [string]$Base = 'D:\IFQ_Runs\confocal_region_map_260808',
    [string]$Confirm = 'D:\IFQ_Runs\confocal_260809_visual_panels_79\visual_merge_panels\Confirm'
)

$ErrorActionPreference = 'Stop'
function RGB([int]$r, [int]$g, [int]$b) { $r + 256 * $g + 65536 * $b }

function Add-Text {
    param($Slide, [string]$Text, [single]$Left, [single]$Top, [single]$Width, [single]$Height,
          [single]$Size = 18, [int]$Color = 0xFFFFFF, [string]$Font = 'Aptos',
          [bool]$Bold = $false, [int]$Align = 1, [int]$Fill = -1, [single]$Margin = 4)
    $shape = $Slide.Shapes.AddTextbox(1, $Left, $Top, $Width, $Height)
    if ($Fill -ge 0) { $shape.Fill.Visible = -1; $shape.Fill.ForeColor.RGB = $Fill } else { $shape.Fill.Visible = 0 }
    $shape.Line.Visible = 0
    $shape.TextFrame2.MarginLeft = $Margin; $shape.TextFrame2.MarginRight = $Margin
    $shape.TextFrame2.MarginTop = $Margin; $shape.TextFrame2.MarginBottom = $Margin
    $shape.TextFrame2.WordWrap = -1
    $shape.TextFrame2.TextRange.Text = $Text
    $shape.TextFrame2.TextRange.Font.Name = $Font; $shape.TextFrame2.TextRange.Font.Size = $Size
    $shape.TextFrame2.TextRange.Font.Bold = $(if ($Bold) { -1 } else { 0 })
    $shape.TextFrame2.TextRange.Font.Fill.ForeColor.RGB = $Color
    $shape.TextFrame2.TextRange.ParagraphFormat.Alignment = $Align
    $shape
}

function Add-FitPicture {
    param($Slide, [string]$Path, [single]$Left, [single]$Top, [single]$Width, [single]$Height)
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($Path)
    try { $ratio = $img.Width / $img.Height } finally { $img.Dispose() }
    if ($ratio -gt ($Width / $Height)) { $w=$Width; $h=$Width/$ratio; $x=$Left; $y=$Top+($Height-$h)/2 }
    else { $h=$Height; $w=$Height*$ratio; $x=$Left+($Width-$w)/2; $y=$Top }
    $Slide.Shapes.AddPicture($Path, 0, -1, $x, $y, $w, $h)
}

function Set-Background($Slide, [int]$Color) {
    $Slide.FollowMasterBackground = 0; $Slide.Background.Fill.Solid(); $Slide.Background.Fill.ForeColor.RGB = $Color
}

function Add-Header($Slide, [string]$Title, [string]$Subtitle) {
    $null = Add-Text $Slide $Title 30 20 900 36 24 (RGB 245 245 247) 'Aptos Display' $true
    $null = Add-Text $Slide $Subtitle 32 55 890 22 11 (RGB 170 177 190)
    $line=$Slide.Shapes.AddShape(1,30,82,900,3); $line.Fill.ForeColor.RGB=RGB 255 232 0; $line.Line.Visible=0
}

function Parse-PanelFile([System.IO.FileInfo]$file) {
    if ($file.Name -notmatch '_(LEFT|RIGHT)_(\d{2})_G(\d{3})_') { return $null }
    $side=$matches[1]; $cycle=[int]$matches[2]; $g=[int]$matches[3]
    $sample = if ($file.Name -match '^M4-1_') {'M4-1'} elseif ($file.Name -match '^M4-2_') {'M4-2'} elseif ($file.Name -match '^M2_') {'M2'} else {'M6'}
    $order = if ($sample -eq 'M4-1' -and $side -eq 'LEFT') { (($cycle - 1) * 2) + $g } else { $g }
    [pscustomobject]@{Sample=$sample;Side=$side;Order=$order;Path=$file.FullName;Width=0;Height=0}
}

function Get-DeckAsset([string]$Path, [string]$Sample, [string]$Side, [int]$Order) {
    Add-Type -AssemblyName System.Drawing
    $assetDir=Join-Path $Base 'deck\retouched_assets'
    [IO.Directory]::CreateDirectory($assetDir)|Out-Null
    $asset=Join-Path $assetDir ("{0}_{1}_G{2:D3}.jpg" -f ($Sample -replace '-','_'),$Side,$Order)
    $src=[System.Drawing.Image]::FromFile($Path)
    try {
        $max=1200
        $scale=[Math]::Min(1.0,$max/[double][Math]::Max($src.Width,$src.Height))
        $w=[int][Math]::Round($src.Width*$scale); $h=[int][Math]::Round($src.Height*$scale)
        $bmp=New-Object System.Drawing.Bitmap($w,$h,[System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $g=[System.Drawing.Graphics]::FromImage($bmp)
        try {
            $g.Clear([System.Drawing.Color]::Black)
            $g.InterpolationMode=[System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.CompositingQuality=[System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $g.DrawImage($src,0,0,$w,$h)
        } finally { $g.Dispose() }
        $codec=[System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders()|Where-Object MimeType -eq 'image/jpeg'
        $ep=New-Object System.Drawing.Imaging.EncoderParameters(1)
        try {
            $ep.Param[0]=New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality,95L)
            $bmp.Save($asset,$codec,$ep)
        } finally { $ep.Dispose(); $bmp.Dispose() }
    } finally { $src.Dispose() }
    $asset
}

function Add-FieldSetSlide($Pres, [int]$Index, $Item, [int]$StartOrder, $FieldLookup) {
    $s=$Pres.Slides.Add($Index,12); Set-Background $s (RGB 8 11 16)
    $end=$StartOrder+4
    Add-Header $s "$($Item.sample) - $($Item.panel): retouched fields $StartOrder-$end" "$($Item.genotype) | $($Item.condition) | acquisition order retained from map"
    $gap=12; $left=24; $top=108; $cellW=172; $cellH=392
    for ($order=$StartOrder; $order -le $end; $order++) {
        $x=$left+($order-$StartOrder)*($cellW+$gap)
        $box=$s.Shapes.AddShape(1,$x,$top,$cellW,$cellH); $box.Fill.ForeColor.RGB=RGB 19 24 33; $box.Line.ForeColor.RGB=RGB 53 61 76
        $label=$s.Shapes.AddShape(1,$x+8,$top+8,38,28); $label.Fill.ForeColor.RGB=RGB 255 232 0; $label.Line.Visible=0
        $null=Add-Text $s ([string]$order) ($x+8) ($top+8) 38 28 14 (RGB 12 15 20) 'Aptos' $true 2 -1 1
        $key="$($Item.sample)|$($Item.side)|$order"
        $field=$FieldLookup[$key]
        if ($field) {
            $assetPath=Get-DeckAsset $field.Path $Item.sample $Item.side $order
            $null=Add-FitPicture $s $assetPath ($x+8) ($top+48) ($cellW-16) ($cellW-16)
            Add-Type -AssemblyName System.Drawing
            $img=[System.Drawing.Image]::FromFile($field.Path); try{$dims="$($img.Width)x$($img.Height)"}finally{$img.Dispose()}
            $status=if($dims -ne '2048x2048'){"PARTIAL IMAGE`n$dims"}else{"G{0:D3}`nRetouched visual merge" -f $order}
            $statusColor=if($dims -ne '2048x2048'){RGB 255 176 45}else{RGB 190 198 211}
            $null=Add-Text $s $status ($x+10) ($top+230) ($cellW-20) 58 11 $statusColor 'Aptos' ($dims -ne '2048x2048') 2
            $channelText=if($Item.side -eq 'LEFT'){"DAPI blue`nKRT5 green`nAGER red`nT1alpha white"}else{"DAPI blue`nProSPC green`nAGER red`nKRT8 white"}
            $null=Add-Text $s $channelText ($x+10) ($top+300) ($cellW-20) 72 10 (RGB 154 165 182) 'Aptos' $false 1
        } else {
            $missing=$s.Shapes.AddShape(1,$x+20,$top+76,$cellW-40,$cellW-40); $missing.Fill.ForeColor.RGB=RGB 39 43 52; $missing.Line.DashStyle=4; $missing.Line.ForeColor.RGB=RGB 255 176 45
            $null=Add-Text $s 'NOT PRESENT IN`nCONFIRM FOLDER' ($x+25) ($top+117) ($cellW-50) 48 12 (RGB 255 176 45) 'Aptos' $true 2
            $null=Add-Text $s ("G{0:D3}`nOrder preserved" -f $order) ($x+10) ($top+250) ($cellW-20) 50 11 (RGB 190 198 211) 'Aptos' $false 2
        }
    }
}

$items=@(
 @{sample='M2';side='LEFT';panel='KRT5 / Ager / T1alpha';genotype='IFNg KO homozygous';condition='PR8 infection';image='M2_krt5_ager_t1a_annotated.jpg'},
 @{sample='M2';side='RIGHT';panel='ProSPC / Ager / KRT8';genotype='IFNg KO homozygous';condition='PR8 infection';image='M2_prospc_ager_krt8_annotated.jpg'},
 @{sample='M6';side='LEFT';panel='KRT5 / Ager / T1alpha';genotype='IFNg KO homozygous';condition='No infection';image='M6_krt5_ager_t1a_annotated.jpg'},
 @{sample='M6';side='RIGHT';panel='ProSPC / Ager / KRT8';genotype='IFNg KO homozygous';condition='No infection';image='M6_prospc_ager_krt8_annotated.jpg'},
 @{sample='M4-1';side='LEFT';panel='KRT5 / Ager / T1alpha';genotype='IFNg KO heterozygous';condition='PR8 infection';image='M4_1_krt5_ager_t1a_annotated.jpg'},
 @{sample='M4-1';side='RIGHT';panel='ProSPC / Ager / KRT8';genotype='IFNg KO heterozygous';condition='PR8 infection';image='M4_1_prospc_ager_krt8_annotated.jpg'},
 @{sample='M4-2';side='LEFT';panel='KRT5 / Ager / T1alpha';genotype='IFNg KO heterozygous';condition='No infection';image='M4_2_krt5_ager_t1a_annotated.jpg'},
 @{sample='M4-2';side='RIGHT';panel='ProSPC / Ager / KRT8';genotype='IFNg KO heterozygous';condition='No infection';image='M4_2_prospc_ager_krt8_annotated.jpg'}
)

$lookup=@{}; $manifest=@()
Get-ChildItem -LiteralPath $Confirm -Recurse -File -Filter '*.png' | ForEach-Object {
    $p=Parse-PanelFile $_; if($p){$lookup["$($p.Sample)|$($p.Side)|$($p.Order)"]=$p; $manifest+=$p}
}

$deckDir=Join-Path $Base 'deck'; $renderDir=Join-Path $deckDir 'rendered_with_retouched'
[IO.Directory]::CreateDirectory($renderDir)|Out-Null
$ppt=New-Object -ComObject PowerPoint.Application; $ppt.Visible=-1; $pres=$ppt.Presentations.Add()
$pres.PageSetup.SlideWidth=960; $pres.PageSetup.SlideHeight=540
try {
    $idx=1
    $s=$pres.Slides.Add($idx++,12); Set-Background $s (RGB 9 12 18)
    $null=Add-Text $s 'Confocal acquisition map + retouched fields' 66 116 830 64 34 (RGB 248 249 251) 'Aptos Display' $true
    $null=Add-Text $s 'Whole-section localization followed by 20x visual-merge panels in acquisition order' 70 196 800 36 19 (RGB 186 195 210)
    $null=Add-Text $s '260808-CW | 80 supplied retouched images | complete LEFT and RIGHT acquisition order' 70 280 820 30 15 (RGB 15 18 23) 'Aptos' $true 1 (RGB 255 232 0) 8
    $null=Add-Text $s 'Retouched panels are display-only visual merges and are not quantified.' 70 344 820 30 12 (RGB 148 158 175)

    foreach($item in $items){
        $s=$pres.Slides.Add($idx++,12); Set-Background $s (RGB 8 11 16)
        Add-Header $s "$($item.sample) - $($item.panel): acquisition map" "$($item.genotype) | $($item.condition) | yellow squares show orders G001-G010"
        $null=Add-FitPicture $s (Join-Path $Base "annotated\$($item.image)") 50 102 860 410
        Add-FieldSetSlide $pres $idx $item 1 $lookup; $idx++
        Add-FieldSetSlide $pres $idx $item 6 $lookup; $idx++
    }

    $pptx=Join-Path $deckDir '260808-CW_confocal_acquisition_map_with_retouched_fields.pptx'
    $pres.SaveAs($pptx,24); $pres.Export($renderDir,'PNG',1600,900)
    $manifest | Sort-Object Sample,Side,Order | Export-Csv -LiteralPath (Join-Path $Base 'metadata\retouched_field_order.csv') -NoTypeInformation -Encoding utf8
    "PPTX=$pptx"; "RENDERED=$renderDir"; "SLIDES=$($pres.Slides.Count)"; "IMAGES=$($manifest.Count)"
} finally {
    if($pres){$pres.Close()}; $ppt.Quit()
    if($pres){[void][Runtime.InteropServices.Marshal]::ReleaseComObject($pres)}
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($ppt); [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}
