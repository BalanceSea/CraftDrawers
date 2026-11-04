"""Import the existing CraftDrawers items' original StorageDrawers assets.

Run from any directory: python scripts/import-storagedrawers-items.py
PNG files are copied without modification from a pinned upstream commit.
Only model namespaces and filenames are adapted; CE item IDs are maintained
in configuration/drawers.yml. This script is not part of the Gradle build.
"""

import concurrent.futures
import hashlib
import json
import struct
import urllib.request
from pathlib import Path


REVISION = "3af5f8e81e381b3aa4c58113362e57b69c3e7e8a"
REPOSITORY = "jaquadro/StorageDrawers"
SOURCE_ASSETS = "common/src/main/resources/assets/storagedrawers"
ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "build/upstream/storagedrawers"
PACK = ROOT / "craftengine-pack/craftdrawers"
ASSETS = PACK / "resourcepack/assets/craftdrawers"
ITEM_MODELS = {
    "upgrade_storage_obsidian": "obsidian_storage_upgrade",
    "upgrade_storage_copper": "copper_storage_upgrade",
    "upgrade_storage_iron": "iron_storage_upgrade",
    "upgrade_storage_gold": "gold_storage_upgrade",
    "upgrade_storage_emerald": "emerald_storage_upgrade",
    "upgrade_storage_diamond": "diamond_storage_upgrade",
    "upgrade_storage_netherite": "netherite_storage_upgrade",
    "upgrade_one_stack": "one_stack_upgrade",
    "upgrade_void": "void_upgrade",
    "upgrade_hopper": "hopper_upgrade",
    "upgrade_magnet_1": "magnet_upgrade",
    "upgrade_magnet_2": "magnet_upgrade_2",
    "upgrade_magnet_3": "magnet_upgrade_3",
    "drawer_key": "drawer_key",
    "personal_key": "personal_key",
    "upgrade_template": "upgrade_template",
}


def request_bytes(url):
    request = urllib.request.Request(url, headers={"User-Agent": "CraftDrawers-resource-import"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def import_assets():
    CACHE.mkdir(parents=True, exist_ok=True)
    tree_file = CACHE / "tree.json"
    if not tree_file.is_file():
        tree_file.write_bytes(request_bytes(f"https://api.github.com/repos/{REPOSITORY}/git/trees/{REVISION}?recursive=1"))
    tree = json.loads(tree_file.read_bytes())
    if tree.get("truncated"):
        raise RuntimeError("Upstream tree is truncated")
    source_files = {entry["path"]: entry for entry in tree["tree"] if entry["type"] == "blob"}

    def fetch(path):
        cached = CACHE / path
        data = cached.read_bytes() if cached.is_file() else request_bytes(
            f"https://raw.githubusercontent.com/{REPOSITORY}/{REVISION}/{path}")
        blob_hash = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        if blob_hash != source_files[path]["sha"]:
            raise RuntimeError(f"Upstream Git blob mismatch: {path}")
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached.write_bytes(data)
        return data

    model_paths = {item: f"{SOURCE_ASSETS}/models/item/{source}.json" for item, source in ITEM_MODELS.items()}
    paths = ["LICENSE", *model_paths.values()]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        downloaded = dict(zip(paths, executor.map(fetch, paths)))
    license_text = downloaded["LICENSE"].decode("utf-8").strip()
    license_file = PACK / "STORAGEDRAWERS-LICENSE.txt"
    if license_file.is_file() and license_text not in license_file.read_text(encoding="utf-8"):
        raise RuntimeError("The existing notice does not contain the pinned upstream MIT license")

    models = {}
    texture_paths = set()
    for item, path in model_paths.items():
        model = json.loads(downloaded[path])
        parent = model.get("parent", "")
        if parent not in {"item/generated", "minecraft:item/generated", "minecraft:item/handheld"}:
            raise RuntimeError(f"Unexpected upstream model parent: {path}: {parent}")
        if parent == "item/generated":
            model["parent"] = "minecraft:item/generated"
        for layer, texture in model["textures"].items():
            if not texture.startswith("storagedrawers:item/") or ".." in texture:
                raise RuntimeError(f"Unexpected source texture: {path}: {texture}")
            resource_path = texture.split(":", 1)[1]
            texture_paths.add(f"{SOURCE_ASSETS}/textures/{resource_path}.png")
            model["textures"][layer] = f"craftdrawers:{resource_path}"
        models[item] = (json.dumps(model, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    texture_paths = sorted(texture_paths)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        textures = dict(zip(texture_paths, executor.map(fetch, texture_paths)))
    for path, data in textures.items():
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR":
            raise RuntimeError(f"Invalid upstream PNG: {path}")

    # All source files and the retained notice are verified before pack writes.
    model_records = []
    for item, data in models.items():
        target = ASSETS / f"models/item/{item}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        source = model_paths[item]
        model_records.append({
            "item_id": f"craftdrawers:{item}",
            "source": source,
            "source_git_blob_sha1": source_files[source]["sha"],
            "source_sha256": sha256(downloaded[source]),
            "file": target.relative_to(PACK).as_posix(),
            "sha256": sha256(data),
        })
    texture_records = []
    for source, data in textures.items():
        target = ASSETS / source.removeprefix(f"{SOURCE_ASSETS}/")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        width, height = struct.unpack(">II", data[16:24])
        texture_records.append({
            "source": source,
            "source_git_blob_sha1": source_files[source]["sha"],
            "file": target.relative_to(PACK).as_posix(),
            "sha256": sha256(data),
            "width": width,
            "height": height,
            "modified": False,
        })
    if not license_file.is_file():
        license_file.write_text("Original assets are from StorageDrawers.\n\n" + license_text + "\n", encoding="utf-8")
    manifest = {
        "schema_version": 1,
        "repository": f"https://github.com/{REPOSITORY}",
        "revision": REVISION,
        "license": "MIT",
        "license_file": license_file.name,
        "license_source_sha256": sha256(downloaded["LICENSE"]),
        "model_changes": "Adapt item filenames and storagedrawers texture namespace; preserve vanilla generated/handheld parents.",
        "models": model_records,
        "textures": texture_records,
    }
    (PACK / "STORAGEDRAWERS-ITEM-ASSETS.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(models)} models and {len(textures)} unmodified textures from StorageDrawers {REVISION}")


if __name__ == "__main__":
    import_assets()
