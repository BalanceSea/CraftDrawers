Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$packRoot = Join-Path $repoRoot "craftengine-pack\craftdrawers"
$textureRoot = Join-Path $packRoot "resourcepack\assets\craftdrawers\textures\block"
$modelRoot = Join-Path $packRoot "resourcepack\assets\minecraft\models\block\craftdrawers"
$configRoot = Join-Path $packRoot "configuration"
$langRoot = Join-Path $configRoot "langs"

$woods = @(
    [pscustomobject]@{ Id = "oak"; Planks = "oak_planks"; Slab = "oak_slab"; NameZh = "橡木"; NameEn = "Oak"; Shadow = "271d12"; Highlight = "c39a62" },
    [pscustomobject]@{ Id = "spruce"; Planks = "spruce_planks"; Slab = "spruce_slab"; NameZh = "云杉"; NameEn = "Spruce"; Shadow = "241811"; Highlight = "a77c55" },
    [pscustomobject]@{ Id = "birch"; Planks = "birch_planks"; Slab = "birch_slab"; NameZh = "白桦"; NameEn = "Birch"; Shadow = "403825"; Highlight = "e8d69f" },
    [pscustomobject]@{ Id = "jungle"; Planks = "jungle_planks"; Slab = "jungle_slab"; NameZh = "丛林"; NameEn = "Jungle"; Shadow = "352216"; Highlight = "c18b61" },
    [pscustomobject]@{ Id = "acacia"; Planks = "acacia_planks"; Slab = "acacia_slab"; NameZh = "金合欢"; NameEn = "Acacia"; Shadow = "332422"; Highlight = "c98460" },
    [pscustomobject]@{ Id = "dark_oak"; Planks = "dark_oak_planks"; Slab = "dark_oak_slab"; NameZh = "深色橡木"; NameEn = "Dark Oak"; Shadow = "17110d"; Highlight = "76553b" },
    [pscustomobject]@{ Id = "mangrove"; Planks = "mangrove_planks"; Slab = "mangrove_slab"; NameZh = "红树"; NameEn = "Mangrove"; Shadow = "341819"; Highlight = "b86659" },
    [pscustomobject]@{ Id = "cherry"; Planks = "cherry_planks"; Slab = "cherry_slab"; NameZh = "樱花木"; NameEn = "Cherry"; Shadow = "40232a"; Highlight = "dfa7b5" },
    [pscustomobject]@{ Id = "bamboo"; Planks = "bamboo_planks"; Slab = "bamboo_slab"; NameZh = "竹子"; NameEn = "Bamboo"; Shadow = "413a21"; Highlight = "d5c27c" },
    [pscustomobject]@{ Id = "crimson"; Planks = "crimson_planks"; Slab = "crimson_slab"; NameZh = "绯红菌柄"; NameEn = "Crimson"; Shadow = "35111b"; Highlight = "a94554" },
    [pscustomobject]@{ Id = "warped"; Planks = "warped_planks"; Slab = "warped_slab"; NameZh = "诡异菌柄"; NameEn = "Warped"; Shadow = "102526"; Highlight = "55a095" }
)

function Write-Utf8File([string]$Path, [string]$Content) {
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Path)) | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-HexChannel([string]$Hex, [int]$Offset) {
    return [Convert]::ToInt32($Hex.Substring($Offset, 2), 16)
}

function Write-RecoloredTexture([string]$SourcePath, [string]$TargetPath, [string]$Shadow, [string]$Highlight) {
    $bitmap = [System.Drawing.Bitmap]::new($SourcePath)
    try {
        $minimum = 255.0
        $maximum = 0.0
        for ($y = 0; $y -lt $bitmap.Height; $y++) {
            for ($x = 0; $x -lt $bitmap.Width; $x++) {
                $pixel = $bitmap.GetPixel($x, $y)
                $luminance = 0.299 * $pixel.R + 0.587 * $pixel.G + 0.114 * $pixel.B
                $minimum = [Math]::Min($minimum, $luminance)
                $maximum = [Math]::Max($maximum, $luminance)
            }
        }

        $shadowChannels = @((Get-HexChannel $Shadow 0), (Get-HexChannel $Shadow 2), (Get-HexChannel $Shadow 4))
        $highlightChannels = @((Get-HexChannel $Highlight 0), (Get-HexChannel $Highlight 2), (Get-HexChannel $Highlight 4))
        for ($y = 0; $y -lt $bitmap.Height; $y++) {
            for ($x = 0; $x -lt $bitmap.Width; $x++) {
                $pixel = $bitmap.GetPixel($x, $y)
                if ($pixel.A -eq 0) { continue }
                $luminance = 0.299 * $pixel.R + 0.587 * $pixel.G + 0.114 * $pixel.B
                $range = [Math]::Max(1.0, $maximum - $minimum)
                $amount = [Math]::Clamp(($luminance - $minimum) / $range, 0.0, 1.0)
                $red = [int][Math]::Round($shadowChannels[0] + ($highlightChannels[0] - $shadowChannels[0]) * $amount)
                $green = [int][Math]::Round($shadowChannels[1] + ($highlightChannels[1] - $shadowChannels[1]) * $amount)
                $blue = [int][Math]::Round($shadowChannels[2] + ($highlightChannels[2] - $shadowChannels[2]) * $amount)
                $bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($pixel.A, $red, $green, $blue))
            }
        }
        [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($TargetPath)) | Out-Null
        $bitmap.Save($TargetPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

$variantItems = [System.Collections.Generic.List[string]]::new()
$variantItems.Add("items:")
$variantBlocks = [System.Collections.Generic.List[string]]::new()
$variantBlocks.Add("blocks:")
$halfFurniture = [System.Collections.Generic.List[string]]::new()
$halfFurniture.Add("# Half drawers use CE furniture: block states cannot provide a vertical half collision shape to vanilla clients.")
$halfFurniture.Add("furniture:")
$recipeLines = [System.Collections.Generic.List[string]]::new()
$recipeLines.Add("recipes:")
$categoryItems = [System.Collections.Generic.List[string]]::new()
$zhItems = [System.Collections.Generic.List[string]]::new()
$enItems = [System.Collections.Generic.List[string]]::new()
$zhBlocks = [System.Collections.Generic.List[string]]::new()
$enBlocks = [System.Collections.Generic.List[string]]::new()
$slotCounts = @(1, 2, 4)

foreach ($wood in $woods) {
    foreach ($form in @("full", "half")) {
        foreach ($slots in $slotCounts) {
            $drawerId = "drawer_$($wood.Id)_$($form)_$slots"
            $itemId = "craftdrawers:$drawerId"
            $displayFormZh = if ($form -eq "half") { "半深度" } else { "常规" }
            $displayFormEn = if ($form -eq "half") { "Half Depth" } else { "Full Depth" }
            $nameZh = "$($wood.NameZh)抽屉（$displayFormZh，$slots 格）"
            $nameEn = "$($wood.NameEn) Drawer ($displayFormEn, $slots slots)"
            $langKey = "item.craftdrawers.$drawerId"
            $zhItems.Add("    $($langKey): $($nameZh)")
            $enItems.Add("    $($langKey): $($nameEn)")
            $zhBlocks.Add("    `"block_name:craftdrawers:$drawerId`": $($nameZh)")
            $enBlocks.Add("    `"block_name:craftdrawers:$drawerId`": $($nameEn)")
            $categoryItems.Add("      - $itemId")

            if ($form -eq "half") {
                $halfFurniture.Add("  ${itemId}:")
                $halfFurniture.Add("    settings:")
                $halfFurniture.Add("      item: $itemId")
                $halfFurniture.Add("    variants:")
                # Keep the old variant so CE can restore existing furniture;
                # the plugin then explicitly switches it to the versioned layout.
                foreach ($variant in @("ground", "wall_aligned")) {
                    $halfFurniture.Add("      ${variant}:")
                    $halfFurniture.Add("        elements:")
                    $halfFurniture.Add("          - type: item_display")
                    $halfFurniture.Add("            item: $itemId")
                    $halfFurniture.Add("            display_transform: none")
                    # ItemDisplayRenderer rotates items by 180 degrees internally;
                    # cancel that rotation so the model's north face stays north.
                    $halfFurniture.Add("            yaw: 180")
                    # The inherited half models already occupy z=8..16. Keep the
                    # display centred so the model offset is not applied twice.
                    $halfFurniture.Add("            position: 0,0.5,0")
                    $halfFurniture.Add("        hitboxes:")
                    # CE offsets invert Z after rotation. At yaw=0, these four
                    # cubes cover world x=0..1, y=0..1, z=0.5..1 exactly.
                    foreach ($height in @("0", "0.5")) {
                        foreach ($horizontal in @("-0.25", "0.25")) {
                            $halfFurniture.Add("          - type: shulker")
                            $halfFurniture.Add("            scale: 0.5")
                            $halfFurniture.Add("            peek: 0")
                            $halfFurniture.Add("            direction: up")
                            $halfFurniture.Add("            position: $horizontal,$height,0.25")
                            $halfFurniture.Add("            blocks_building: true")
                            $halfFurniture.Add("            can_be_hit_by_projectile: false")
                            $halfFurniture.Add("            interactive: true")
                            $halfFurniture.Add("            interaction_entity: true")
                            $halfFurniture.Add("            invisible: true")
                        }
                    }
                }
                $halfFurniture.Add("")
            }

            if ($wood.Id -ne "oak") {
                $modelName = if ($form -eq "half") { "$($wood.Id)_drawer_half_$slots" } else { "$($wood.Id)_drawer_$slots" }
                $oakParent = if ($form -eq "half") { "oak_drawer_half_$slots" } else { "oak_drawer_$slots" }
                $textureBase = "craftdrawers:block/drawers_$($wood.Id)"
                $parentId = "minecraft:block/craftdrawers/$oakParent"
                if ($form -eq "half") {
                    $model = [ordered]@{
                        parent = $parentId
                        textures = [ordered]@{
                            particle = "${textureBase}_side"
                            front = "${textureBase}_front_$slots"
                            side = "${textureBase}_side"
                            planks = "minecraft:block/$($wood.Planks)"
                        }
                    }
                } else {
                    $model = [ordered]@{
                        parent = $parentId
                        textures = [ordered]@{
                            particle = "${textureBase}_side"
                            north = "${textureBase}_front_$slots"
                            south = "${textureBase}_side"
                            east = "${textureBase}_side"
                            west = "${textureBase}_side"
                            up = "minecraft:block/$($wood.Planks)"
                            down = "minecraft:block/$($wood.Planks)"
                        }
                    }
                }
                $modelPath = Join-Path $modelRoot "$modelName.json"
                Write-Utf8File $modelPath ($model | ConvertTo-Json -Depth 6)

                $modelId = "minecraft:block/craftdrawers/$modelName"
                $variantItems.Add("  ${itemId}:")
                $variantItems.Add("    material: paper")
                $variantItems.Add("    data:")
                $variantItems.Add("      item_name: <!i><lang:$langKey>")
                $variantItems.Add("    model: $modelId")
                if ($form -eq "full") {
                    $variantItems.Add("    behavior:")
                    $variantItems.Add("      type: block_item")
                    $variantItems.Add("      block: $itemId")
                }
                $variantItems.Add("")

                $variantBlocks.Add("  ${itemId}:")
                $variantBlocks.Add("    settings:")
                $variantBlocks.Add("      item: $itemId")
                $variantBlocks.Add("      hardness: 2.0")
                $variantBlocks.Add("      resistance: 3.0")
                $variantBlocks.Add("      is-suffocating: true")
                $variantBlocks.Add("      is-redstone-conductor: true")
                $variantBlocks.Add("      push-reaction: block")
                $variantBlocks.Add("      instrument: bass")
                $variantBlocks.Add('      tags: ["minecraft:mineable/axe"]')
                $variantBlocks.Add("      sounds:")
                $variantBlocks.Add("        break: minecraft:block.wood.break")
                $variantBlocks.Add("        fall: minecraft:block.wood.fall")
                $variantBlocks.Add("        hit: minecraft:block.wood.hit")
                $variantBlocks.Add("        place: minecraft:block.wood.place")
                $variantBlocks.Add("        step: minecraft:block.wood.step")
                $variantBlocks.Add("    state:")
                $variantBlocks.Add("      properties:")
                $variantBlocks.Add("        facing:")
                $variantBlocks.Add("          type: horizontal_direction")
                $variantBlocks.Add("          default: north")
                $variantBlocks.Add("      appearances:")
                $variantBlocks.Add("        north:")
                $variantBlocks.Add("          auto-state: note_block")
                $variantBlocks.Add("          model: $modelId")
                $variantBlocks.Add("        east:")
                $variantBlocks.Add("          auto-state: note_block")
                $variantBlocks.Add("          model: {path: `"$modelId`", y: 90}")
                $variantBlocks.Add("        south:")
                $variantBlocks.Add("          auto-state: note_block")
                $variantBlocks.Add("          model: {path: `"$modelId`", y: 180}")
                $variantBlocks.Add("        west:")
                $variantBlocks.Add("          auto-state: note_block")
                $variantBlocks.Add("          model: {path: `"$modelId`", y: 270}")
                $variantBlocks.Add("      variants:")
                foreach ($direction in @("north", "east", "south", "west")) {
                    $variantBlocks.Add("        facing=$direction`: {appearance: $direction}")
                }
                $variantBlocks.Add("")

                foreach ($texture in @("side", "front_1", "front_2", "front_4")) {
                    $sourceTexture = if ($texture -eq "side") { "drawers_oak_side.png" } else { "drawers_oak_$texture.png" }
                    $targetTexture = "drawers_$($wood.Id)_$texture.png"
                    Write-RecoloredTexture (Join-Path $textureRoot $sourceTexture) (Join-Path $textureRoot $targetTexture) $wood.Shadow $wood.Highlight
                }
            }

            $recipePath = "craftdrawers:recipe_$drawerId"
            $patternKey = $form + "_" + $slots
            $pattern = switch ($patternKey) {
                "full_1" { @("PPP", " C ", "PPP") }
                "full_2" { @("PCP", "PPP", "PCP") }
                "full_4" { @("CPC", "PPP", "CPC") }
                "half_1" { @("PPP", " C ", "PPP") }
                "half_2" { @("PCP", "PPP", "PCP") }
                "half_4" { @("CPC", "PPP", "CPC") }
            }
            $ingredientMaterial = if ($form -eq "half") { $wood.Slab } else { $wood.Planks }
            $recipeCount = $slots
            $recipeLines.Add("  ${recipePath}:")
            $recipeLines.Add("    type: shaped")
            $recipeLines.Add("    category: building")
            $recipeLines.Add("    group: craftdrawers_${form}_drawers_$slots")
            $recipeLines.Add("    pattern:")
            foreach ($row in $pattern) { $recipeLines.Add("      - `"$row`"") }
            $recipeLines.Add("    ingredients:")
            $recipeLines.Add("      P: minecraft:$ingredientMaterial")
            $recipeLines.Add("      C: minecraft:chest")
            $recipeLines.Add("    result:")
            $recipeLines.Add("      id: $itemId")
            $recipeLines.Add("      count: $recipeCount")
            $recipeLines.Add("")

            $templateRecipeId = "craftdrawers:upgrade_template_from_$drawerId"
            $recipeLines.Add("  ${templateRecipeId}:")
            $recipeLines.Add("    type: shaped")
            $recipeLines.Add("    category: misc")
            $recipeLines.Add("    group: craftdrawers_upgrade_template")
            $recipeLines.Add("    pattern:")
            $recipeLines.Add('      - "SSS"')
            $recipeLines.Add('      - "SDS"')
            $recipeLines.Add('      - "SSS"')
            $recipeLines.Add("    ingredients:")
            $recipeLines.Add("      S: minecraft:stick")
            $recipeLines.Add("      D: $itemId")
            $recipeLines.Add("    result:")
            $recipeLines.Add("      id: craftdrawers:upgrade_template")
            $recipeLines.Add("      count: 4")
            $recipeLines.Add("")
        }
    }
}

$upgradeTemplateItem = "craftdrawers:upgrade_template"
$upgradeLabels = @(
    [pscustomobject]@{ Id = "upgrade_storage_obsidian"; Zh = "黑曜石容量升级（2 倍）"; En = "Obsidian Storage Upgrade (2x)" },
    [pscustomobject]@{ Id = "upgrade_storage_copper"; Zh = "铜容量升级（4 倍）"; En = "Copper Storage Upgrade (4x)" },
    [pscustomobject]@{ Id = "upgrade_storage_iron"; Zh = "铁容量升级（8 倍）"; En = "Iron Storage Upgrade (8x)" },
    [pscustomobject]@{ Id = "upgrade_storage_gold"; Zh = "金容量升级（16 倍）"; En = "Gold Storage Upgrade (16x)" },
    [pscustomobject]@{ Id = "upgrade_storage_emerald"; Zh = "绿宝石容量升级（64 倍）"; En = "Emerald Storage Upgrade (64x)" },
    [pscustomobject]@{ Id = "upgrade_storage_diamond"; Zh = "钻石容量升级（256 倍）"; En = "Diamond Storage Upgrade (256x)" },
    [pscustomobject]@{ Id = "upgrade_storage_netherite"; Zh = "下界合金容量升级（2048 倍）"; En = "Netherite Storage Upgrade (2048x)" },
    [pscustomobject]@{ Id = "upgrade_one_stack"; Zh = "单组升级"; En = "One Stack Upgrade" },
    [pscustomobject]@{ Id = "upgrade_void"; Zh = "虚空升级"; En = "Void Upgrade" },
    [pscustomobject]@{ Id = "upgrade_hopper"; Zh = "漏斗升级"; En = "Hopper Upgrade" },
    [pscustomobject]@{ Id = "upgrade_magnet_1"; Zh = "磁铁升级 I"; En = "Magnet Upgrade I" },
    [pscustomobject]@{ Id = "upgrade_magnet_2"; Zh = "磁铁升级 II"; En = "Magnet Upgrade II" },
    [pscustomobject]@{ Id = "upgrade_magnet_3"; Zh = "磁铁升级 III"; En = "Magnet Upgrade III" },
    [pscustomobject]@{ Id = "drawer_key"; Zh = "抽屉钥匙"; En = "Drawer Key" },
    [pscustomobject]@{ Id = "personal_key"; Zh = "私人钥匙"; En = "Personal Key" },
    [pscustomobject]@{ Id = "upgrade_template"; Zh = "升级模板"; En = "Upgrade Template" }
)

$zhItems.Add("    item.craftdrawers.upgrade_template: 升级模板")
$enItems.Add("    item.craftdrawers.upgrade_template: Upgrade Template")
$categoryItems.Add("      - craftdrawers:upgrade_template")

$upgradeRecipes = @(
    @{ Id = "upgrade_storage_obsidian"; Material = "minecraft:obsidian"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_storage_copper"; Material = "minecraft:copper_ingot"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_storage_iron"; Material = "minecraft:iron_ingot"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_storage_gold"; Material = "minecraft:gold_ingot"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_storage_emerald"; Material = "minecraft:emerald"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_storage_diamond"; Material = "minecraft:diamond"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_storage_netherite"; Material = "minecraft:netherite_ingot"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_one_stack"; Material = "minecraft:flint"; Pattern = @("SSS", "MCM", "SSS"); MaterialKey = "M" },
    @{ Id = "upgrade_void"; Material = "minecraft:obsidian"; Pattern = @("MMM", "MCM", "MMM"); MaterialKey = "M" },
    @{ Id = "upgrade_hopper"; Material = "minecraft:hopper"; Pattern = @(" H ", "SCS", "SSS"); MaterialKey = "H" },
    @{ Id = "upgrade_magnet_1"; Material = "minecraft:copper_ingot"; Pattern = @("M/M", "/H/", "M/M"); MaterialKey = "M" },
    @{ Id = "upgrade_magnet_2"; Material = "minecraft:copper_ingot"; Pattern = @("M/M", "/H/", "M/M"); MaterialKey = "M" },
    @{ Id = "upgrade_magnet_3"; Material = "minecraft:copper_ingot"; Pattern = @("M/M", "/H/", "M/M"); MaterialKey = "M" }
)

foreach ($upgrade in $upgradeRecipes) {
    $recipeLines.Add("  craftdrawers:recipe_$($upgrade.Id):")
    $recipeLines.Add("    type: shaped")
    $recipeLines.Add("    category: equipment")
    $recipeLines.Add("    pattern:")
    foreach ($row in $upgrade.Pattern) { $recipeLines.Add("      - `"$row`"") }
    $recipeLines.Add("    ingredients:")
    if ($upgrade.Id -eq "upgrade_magnet_1" -or $upgrade.Id -eq "upgrade_magnet_2" -or $upgrade.Id -eq "upgrade_magnet_3") {
        $recipeLines.Add("      M: minecraft:copper_ingot")
        $recipeLines.Add("      /: minecraft:iron_bars")
        $lower = if ($upgrade.Id -eq "upgrade_magnet_1") { "upgrade_hopper" } elseif ($upgrade.Id -eq "upgrade_magnet_2") { "upgrade_magnet_1" } else { "upgrade_magnet_2" }
        $recipeLines.Add("      H: craftdrawers:$lower")
    } elseif ($upgrade.Id -eq "upgrade_hopper") {
        $recipeLines.Add("      H: minecraft:hopper")
        $recipeLines.Add("      S: minecraft:stick")
        $recipeLines.Add("      C: $upgradeTemplateItem")
    } else {
        $recipeLines.Add("      M: $($upgrade.Material)")
        if ($upgrade.Id -eq "upgrade_void") { } else { $recipeLines.Add("      S: minecraft:stick") }
        $recipeLines.Add("      C: $upgradeTemplateItem")
    }
    $recipeLines.Add("    result:")
    $recipeLines.Add("      id: craftdrawers:$($upgrade.Id)")
    $recipeLines.Add("      count: 1")
    $recipeLines.Add("")
}

$recipeLines.Add("  craftdrawers:recipe_drawer_key:")
$recipeLines.Add("    type: shaped")
$recipeLines.Add("    category: equipment")
$recipeLines.Add("    pattern:")
$recipeLines.Add('      - "-M"')
$recipeLines.Add('      - " M"')
$recipeLines.Add('      - " C"')
$recipeLines.Add("    ingredients:")
$recipeLines.Add('      "-": minecraft:gold_nugget')
$recipeLines.Add("      M: minecraft:gold_ingot")
$recipeLines.Add("      C: $upgradeTemplateItem")
$recipeLines.Add("    result:")
$recipeLines.Add("      id: craftdrawers:drawer_key")
$recipeLines.Add("      count: 1")
$recipeLines.Add("")
$recipeLines.Add("  craftdrawers:recipe_personal_key:")
$recipeLines.Add("    type: shapeless")
$recipeLines.Add("    category: equipment")
$recipeLines.Add("    ingredients:")
$recipeLines.Add("      - craftdrawers:drawer_key")
$recipeLines.Add("      - minecraft:name_tag")
$recipeLines.Add("    result:")
$recipeLines.Add("      id: craftdrawers:personal_key")
$recipeLines.Add("      count: 1")

foreach ($upgrade in $upgradeLabels) {
    $id = $upgrade.Id
    $nameZh = $upgrade.Zh
    $nameEn = $upgrade.En
    if ($id -eq "upgrade_template") { continue }
    $zhItems.Add("    item.craftdrawers.$($id): $($nameZh)")
    $enItems.Add("    item.craftdrawers.$($id): $($nameEn)")
    $categoryItems.Add("      - craftdrawers:$id")
}

$variantLines = [System.Collections.Generic.List[string]]::new()
$variantLines.AddRange($variantItems)
$variantLines.Add("")
$variantLines.AddRange($variantBlocks)
Write-Utf8File (Join-Path $configRoot "wood-variants.yml") ($variantLines -join "`n")
Write-Utf8File (Join-Path $configRoot "half-drawers-furniture.yml") ($halfFurniture -join "`n")
Write-Utf8File (Join-Path $configRoot "recipes.yml") ($recipeLines -join "`n")

$categoryLines = [System.Collections.Generic.List[string]]::new()
$categoryLines.Add("categories:")
$categoryLines.Add("  craftdrawers:main:")
$categoryLines.Add("    priority: 100")
$categoryLines.Add("    name: <!i><#c89b62>CraftDrawers 抽屉与升级")
$categoryLines.Add("    icon: craftdrawers:drawer_oak_full_1")
$categoryLines.Add("    list:")
foreach ($item in $categoryItems) { $categoryLines.Add($item) }
Write-Utf8File (Join-Path $configRoot "categories.yml") ($categoryLines -join "`n")

$zhLines = [System.Collections.Generic.List[string]]::new()
$zhLines.Add("lang#items:")
$zhLines.Add("  zh_cn:")
foreach ($line in $zhItems) { $zhLines.Add($line) }
$zhLines.Add("")
$zhLines.Add("lang#blocks:")
$zhLines.Add("  zh_cn:")
foreach ($line in $zhBlocks) { $zhLines.Add($line) }
$zhLines.Add("")
$zhLines.Add("translations#categories:")
$zhLines.Add("  zh_cn:")
$zhLines.Add("    category.craftdrawers.main: 抽屉与升级")
Write-Utf8File (Join-Path $langRoot "zh_cn.yml") ($zhLines -join "`n")

$enLines = [System.Collections.Generic.List[string]]::new()
$enLines.Add("lang#items:")
$enLines.Add("  en_us:")
foreach ($line in $enItems) { $enLines.Add($line) }
$enLines.Add("")
$enLines.Add("lang#blocks:")
$enLines.Add("  en_us:")
foreach ($line in $enBlocks) { $enLines.Add($line) }
$enLines.Add("")
$enLines.Add("translations#categories:")
$enLines.Add("  en_us:")
$enLines.Add("    category.craftdrawers.main: CraftDrawers Drawers and Upgrades")
Write-Utf8File (Join-Path $langRoot "en_us.yml") ($enLines -join "`n")

Write-Output "Generated $($woods.Count - 1) additional wood variants, $($woods.Count * 6) drawer recipes, texture variants, category, and language files."
