#!/usr/bin/env python3
import hashlib
import os
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

R = Path(__file__).resolve().parent
VERSION_NAME = os.environ.get("VERSION_NAME", "2.2.0")
VERSION_CODE = os.environ.get("VERSION_CODE", "202")


def sdk_roots():
    roots = []
    for env_key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        val = os.environ.get(env_key)
        if val:
            roots.append(Path(val).expanduser())
    home = Path.home()
    roots.extend([
        home / "Library/Android/sdk",
        home / "Android/Sdk",
        Path("/opt/homebrew/share/android-commandlinetools"),
        Path("/usr/local/share/android-commandlinetools"),
    ])
    return [p for p in roots if p.exists()]


def resolve_android_jar():
    env_val = os.environ.get("ANDROID_JAR")
    if env_val:
        p = Path(env_val).expanduser()
        if p.is_file():
            return p
        raise SystemExit(f"ANDROID_JAR does not exist: {p}")
    for sdk in sdk_roots():
        jars = sorted((sdk / "platforms").glob("android-*/android.jar"), reverse=True)
        if jars:
            return jars[0]
    raise SystemExit("Unable to locate android.jar. Set ANDROID_JAR or ANDROID_SDK_ROOT.")


def resolve_build_tool(env_name, binary_name):
    env_val = os.environ.get(env_name)
    if env_val:
        p = Path(env_val).expanduser()
        if p.is_file():
            return p
        raise SystemExit(f"{env_name} does not exist: {p}")
    for sdk in sdk_roots():
        candidates = sorted((sdk / "build-tools").glob(f"*/{binary_name}"), reverse=True)
        for c in candidates:
            if c.is_file():
                return c
    which_val = shutil.which(binary_name)
    if which_val:
        return Path(which_val)
    raise SystemExit(f"Unable to locate {binary_name}. Set {env_name} or ANDROID_SDK_ROOT.")


def resolve_java_bin():
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]).expanduser() / "bin")
    candidates.extend([
        Path("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin"),
        Path("/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin"),
    ])
    for c in candidates:
        if (c / "javac").is_file():
            return c
    javac_path = shutil.which("javac")
    if javac_path:
        return Path(javac_path).resolve().parent
    raise SystemExit("Unable to locate JDK (javac). Set JAVA_HOME.")


ANDROID_JAR = resolve_android_jar()
AAPT2 = resolve_build_tool("AAPT2", "aapt2")
APKSIGNER = resolve_build_tool("APKSIGNER", "apksigner")
ZIPALIGN = resolve_build_tool("ZIPALIGN", "zipalign")
J = resolve_java_bin()

os.environ["JAVA_HOME"] = str(J.parent)
os.environ["PATH"] = str(J) + os.pathsep + os.environ.get("PATH", "")

for name, url, digest in [
    (
        "r8.jar",
        "https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.28/r8-9.5.28.jar",
        "38a9ffaaebbd17d1514e3ecbff5a85b989bb13c551b9e224c7409514eea84ff5",
    ),
    (
        "json.jar",
        "https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar",
        "3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796",
    ),
]:
    dep = R / "build/tools" / name
    dep.parent.mkdir(parents=True, exist_ok=True)
    if not dep.exists():
        with urllib.request.urlopen(url, timeout=45) as response:
            dep.write_bytes(response.read())
    if hashlib.sha256(dep.read_bytes()).hexdigest() != digest:
        raise SystemExit("Dependency checksum failed: " + name)

B = R / "build/lsp"
for n in ["classes", "dex", "res"]:
    d = B / n
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)

sources = (
    list((R / "helper/api").rglob("*.java"))
    + list((R / "helper/src").rglob("*.java"))
    + list((R / "src").rglob("*.java"))
)
subprocess.run(
    [
        str(J / "javac"),
        "--release",
        "8",
        "-Xlint:-options",
        "-cp",
        str(R / "build/tools/json.jar") + os.pathsep + str(ANDROID_JAR),
        "-d",
        str(B / "classes"),
        *map(str, sources),
    ],
    check=True,
)
subprocess.run(
    [
        str(J / "java"),
        "-cp",
        str(R / "build/tools/r8.jar"),
        "com.android.tools.r8.D8",
        "--release",
        "--min-api",
        "26",
        "--lib",
        str(ANDROID_JAR),
        "--classpath",
        str(B / "classes"),
        "--output",
        str(B / "dex"),
        *map(str, (B / "classes/dev").rglob("*.class")),
    ],
    check=True,
)
subprocess.run([str(AAPT2), "compile", "--dir", str(R / "helper/res"), "-o", str(B / "res")], check=True)
subprocess.run(
    [
        str(AAPT2),
        "link",
        "-o",
        str(B / "unsigned.apk"),
        "-I",
        str(ANDROID_JAR),
        "--manifest",
        str(R / "helper/AndroidManifest.xml"),
        "--min-sdk-version",
        "26",
        "--target-sdk-version",
        "32",
        "--version-code",
        str(VERSION_CODE),
        "--version-name",
        str(VERSION_NAME),
        *map(str, (B / "res").rglob("*.flat")),
    ],
    check=True,
)
with zipfile.ZipFile(B / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(B / "dex/classes.dex", "classes.dex")
    z.write(R / "helper/assets/xposed_init", "assets/xposed_init")
subprocess.run([str(ZIPALIGN), "-f", "4", str(B / "unsigned.apk"), str(B / "aligned.apk")], check=True)

default_out_dir = R / "dist"
out = Path(os.environ.get("OUTPUT_DIR", str(default_out_dir))) / f"yoyo-bridge-lsp-{VERSION_NAME}.apk"
out.parent.mkdir(parents=True, exist_ok=True)

keystore = Path(os.environ.get("KEYSTORE_PATH", str(R / "build/helper/helper.keystore")))
ks_pass = os.environ.get("KEYSTORE_PASS", "android")
key_alias = os.environ.get("KEY_ALIAS", "yoyo-bridge")
key_pass = os.environ.get("KEY_PASS", ks_pass)
if not keystore.exists():
    keystore.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            str(J / "keytool"),
            "-genkeypair",
            "-keystore",
            str(keystore),
            "-storepass",
            ks_pass,
            "-alias",
            key_alias,
            "-keypass",
            key_pass,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=YOYO Bridge",
        ],
        check=True,
    )
subprocess.run(
    [
        str(APKSIGNER),
        "sign",
        "--ks",
        str(keystore),
        "--ks-key-alias",
        key_alias,
        "--ks-pass",
        f"pass:{ks_pass}",
        "--key-pass",
        f"pass:{key_pass}",
        "--out",
        str(out),
        str(B / "aligned.apk"),
    ],
    check=True,
)
subprocess.run([str(APKSIGNER), "verify", "--verbose", str(out)], check=True)
print(out)
