"""Generate non-categorized CE drawer-face icons used by the contents GUI."""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "craftengine-pack/craftdrawers"
WOODS = (
    "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
    "mangrove", "cherry", "bamboo", "crimson", "warped",
)
SLOT_COUNTS = (1, 2, 4)


def main():
    models_dir = PACK / "resourcepack/assets/craftdrawers/models/item"
    textures_dir = PACK / "resourcepack/assets/craftdrawers/textures/block"
    models_dir.mkdir(parents=True, exist_ok=True)
    items = ["items:"]

    for wood in WOODS:
        for slots in SLOT_COUNTS:
            texture = f"drawers_{wood}_front_{slots}"
            if not (textures_dir / f"{texture}.png").is_file():
                raise FileNotFoundError(f"Missing contents-GUI texture: {texture}.png")
            model_name = f"gui_drawer_front_{wood}_{slots}"
            model = {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": f"craftdrawers:block/{texture}"},
            }
            (models_dir / f"{model_name}.json").write_text(
                json.dumps(model, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
            items.extend([
                f"  craftdrawers:gui_drawer_{wood}_{slots}:",
                "    material: paper",
                "    data:",
                "      item_name: <!i><lang:item.craftdrawers.gui_background>",
                f"    model: craftdrawers:item/{model_name}",
                "",
            ])

    target = PACK / "configuration/gui-backgrounds.yml"
    target.write_text("\n".join(items).rstrip() + "\n", encoding="utf-8")
    print(f"Generated {len(WOODS) * len(SLOT_COUNTS)} non-categorized GUI background items and item models")


if __name__ == "__main__":
    main()
