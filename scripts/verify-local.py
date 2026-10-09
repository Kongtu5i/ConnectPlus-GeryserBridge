"""Compile sources and run all JUnit tests when the desktop sandbox blocks Gradle workers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def run(command):
    completed = subprocess.run(command, cwd=ROOT, encoding="utf-8", errors="replace",
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(completed.stdout, end="")
    if completed.returncode:
        raise RuntimeError(f"Command failed ({completed.returncode}): {command[0]}")


def pack(directory, destination, resources=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for source_root in [directory] + ([resources] if resources else []):
            for source in sorted(source_root.rglob("*")):
                if source.is_file():
                    info = zipfile.ZipInfo(source.relative_to(source_root).as_posix(), (1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(info, source.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-dir", type=Path, default=ROOT / "build/verification")
    args = parser.parse_args()
    host = json.loads((args.classpath_dir / "host-classpath.json").read_text())
    tests = json.loads((args.classpath_dir / "test-classpath.json").read_text())
    expected = json.loads((ROOT / "gradle/host-dependency-sha256.json").read_text())
    if any(Path(entry).suffix != ".jar" for entry in host):
        raise RuntimeError("Host classpath must contain only real dependency JARs")
    actual = {Path(entry).name: hashlib.sha256(Path(entry).read_bytes()).hexdigest() for entry in host}
    if len(actual) != len(host) or actual != expected:
        raise RuntimeError("Host dependencies differ from the locked checksums")
    work = ROOT / "build/verification/local" / uuid.uuid4().hex[:12]
    driver = work / "driver"
    driver.mkdir(parents=True, exist_ok=True)
    run(["javac", "-encoding", "UTF-8", "-d", str(driver), str(ROOT / "scripts/CompileJava.java")])

    def compile_sources(name, sources, classpath, release=21):
        output = work / name
        output.mkdir(exist_ok=True)
        options = ["-encoding", "UTF-8", "-proc:none", "--release", str(release),
                   "-classpath", os.pathsep.join(map(str, classpath)), "-d", str(output), "--sources--"]
        arguments = work / f"{name}.txt"
        arguments.write_text("\n".join(options + list(map(str, sources))), encoding="utf-8")
        run(["java", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
             "-cp", str(driver), "CompileJava", str(arguments)])
        return output

    main_classes = compile_sources("main", sorted((ROOT / "src/main/java").rglob("*.java")), host)
    artifact = ROOT / "build/libs/connectplus-geyserbridge-1.0.2.jar"
    pack(main_classes, artifact, ROOT / "src/main/resources")
    test_sources = sorted((ROOT / "src/test/java").rglob("*.java"))
    test_classes = compile_sources("test", test_sources + [ROOT / "scripts/TestRunner.java"], tests + [str(artifact)])
    test_jar = work / "tests.jar"
    pack(test_classes, test_jar)
    selectors = [re.search(r"^package\s+([\w.]+);", source.read_text(encoding="utf-8"), re.M).group(1)
                 + "." + source.stem for source in test_sources if source.name.endswith("Test.java")]
    run(["java", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(tests + [str(artifact), str(test_jar)]),
         "TestRunner", str(work / "test-summary.json")] + selectors)
    (work.parent / "test-summary.json").write_bytes((work / "test-summary.json").read_bytes())
    print("PASS: extension compilation, packaging and all project JUnit tests")


if __name__ == "__main__":
    main()
