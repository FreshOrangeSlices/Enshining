"""Builds a Modrinth modpack (.mrpack) for testing Enshining: Forge 1.20.1, GeckoLib from Modrinth, and the jar just built.

Usage: python3 tools/make_mrpack.py <enshining jar> <out.mrpack> <pack version>
Import the .mrpack in the Modrinth App (File > Add instance > From file, or just open it).
"""
import json
import sys
import urllib.request
import zipfile

GECKOLIB_VERSION_ID = "aC5KMoNg"  # GeckoLib 4.8.4 for Forge 1.20.1, as in gradle.properties
MINECRAFT = "1.20.1"
FORGE = "47.4.28"


def main():
    jar, out, version = sys.argv[1], sys.argv[2], sys.argv[3]
    req = urllib.request.Request(f"https://api.modrinth.com/v2/version/{GECKOLIB_VERSION_ID}",
                                 headers={"User-Agent": "FreshOrangeSlices/Enshining (mrpack builder)"})
    geckolib = json.load(urllib.request.urlopen(req))
    f = next(x for x in geckolib["files"] if x.get("primary")) if any(x.get("primary") for x in geckolib["files"]) else geckolib["files"][0]

    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Enshining Test",
        "summary": "Enshining with everything it needs, for testing.",
        "files": [{
            "path": f"mods/{f['filename']}",
            "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
            "env": {"client": "required", "server": "required"},
            "downloads": [f["url"]],
            "fileSize": f["size"],
        }],
        "dependencies": {"minecraft": MINECRAFT, "forge": FORGE},
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        # Enshining isn't on Modrinth, so its jar rides along as an override.
        z.write(jar, "overrides/mods/" + jar.replace("\\", "/").split("/")[-1])
    print("wrote", out, "with", f["filename"])


if __name__ == "__main__":
    main()
