"""Build and smoke-check an isolated acceptance package with byte-identical official hosts."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CP = ROOT.parent / "connectplus"
CAPABILITIES = ["verified-xuid", "exact-channel-binding", "targeted-disconnect"]
GEYSER_VERSION, GEYSER_BUILD = "2.11.3", 1247
VIAPROXY_VERSION = "3.4.13"
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def get_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "ConnectPlus-official-host-verification"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


def free_port(kind):
    with socket.socket(socket.AF_INET, kind) as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def create_package_directory(root):
    package = root / "build/verification/acceptance" / uuid.uuid4().hex[:12] / "ConnectPlus-Bedrock-Acceptance-Official"
    package.mkdir(parents=True, exist_ok=False)
    return package


def main():
    tests = json.loads((ROOT / "build/verification/local/test-summary.json").read_text())
    assert tests["tests"] > 0 and tests["failed"] == 0 and tests["skipped"] == 0, tests
    reports = list((CP / "build/test-results/test").glob("TEST-*.xml"))
    counts = {key: sum(int(ET.parse(p).getroot().get(key, 0)) for p in reports)
              for key in ("tests", "failures", "errors", "skipped")}
    assert counts["tests"] >= 700 and not any(counts[k] for k in ("failures", "errors", "skipped")), counts

    official = ROOT / "build/verification/official-hosts"
    official.mkdir(parents=True, exist_ok=True)
    metadata = get_json(f"https://download.geysermc.org/v2/projects/geyser/versions/{GEYSER_VERSION}/builds/{GEYSER_BUILD}")
    geyser_url = f"https://download.geysermc.org/v2/projects/geyser/versions/{GEYSER_VERSION}/builds/{GEYSER_BUILD}/downloads/viaproxy"
    geyser = official / f"Geyser-ViaProxy-{GEYSER_VERSION}-{GEYSER_BUILD}.jar"
    if not geyser.exists():
        urllib.request.urlretrieve(geyser_url, geyser)
    assert sha(geyser) == metadata["downloads"]["viaproxy"]["sha256"], "Official Geyser checksum mismatch"

    release_url = f"https://api.github.com/repos/ViaVersion/ViaProxy/releases/tags/v{VIAPROXY_VERSION}"
    release = get_json(release_url)
    asset = next(a for a in release["assets"] if a["name"] == f"ViaProxy-{VIAPROXY_VERSION}.jar")
    via = official / asset["name"]
    if not via.exists():
        urllib.request.urlretrieve(asset["browser_download_url"], via)
    digest = asset.get("digest")
    if digest:
        assert digest == "sha256:" + sha(via), "Official ViaProxy checksum mismatch"
    assert sha(via) == (ROOT / "gradle/viaproxy-sha256.txt").read_text().strip(), "ViaProxy baseline differs"
    (official / "viaproxy-release.json").write_text(json.dumps(release, indent=2), encoding="utf-8")

    package = create_package_directory(ROOT)
    previous = ROOT / "build/acceptance/ConnectPlus-Bedrock-Acceptance"
    files = {
        "viaproxy.jar": via,
        "plugins/Geyser-ViaProxy.jar": geyser,
        "plugins/ConnectPlus-0.1.0.jar": CP / "build/libs/ConnectPlus-0.1.0.jar",
        "plugins/Geyser/extensions/connectplus-geyserbridge-1.0.1.jar": ROOT / "build/libs/connectplus-geyserbridge-1.0.1.jar",
        "viaproxy.yml": previous / "viaproxy.yml",
        "plugins/ConnectPlus/config.yml": previous / "plugins/ConnectPlus/config.yml",
        "plugins/Geyser/config.yml": previous / "plugins/Geyser/config.yml",
        "start.bat": previous / "start.bat",
        "Geyser-LICENSE": previous / "Geyser-LICENSE",
        "geyser-bridge-v1.md": ROOT / "docs/geyser-bridge-v1.md",
        "真人验收记录.md": CP / "docs/superpowers/notes/bedrock-real-client-acceptance-2026-10-04.md",
        "AGENTS.md": ROOT / "AGENTS.md",
    }
    for name, source in files.items():
        target = package / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    bridge = package / "plugins/Geyser/extensions/connectplus-geyserbridge-1.0.1.jar"
    with zipfile.ZipFile(bridge) as archive:
        assert not any(n.startswith(("org/geysermc/", "net/raphimc/")) for n in archive.namelist()), "Bundled host classes"
        assert not any("/duplicate/" in n for n in archive.namelist()), "Obsolete admission hooks"
        assert b"version: 1.0.1" in archive.read("extension.yml")
    with zipfile.ZipFile(geyser) as archive:
        assert not any(n.endswith("/SessionDuplicateAdmissionEvent.class") for n in archive.namelist()), "Patched Geyser supplied"
    assert sha(package / "viaproxy.jar") == sha(via)
    assert sha(package / "plugins/Geyser-ViaProxy.jar") == metadata["downloads"]["viaproxy"]["sha256"]

    readme = """# ConnectPlus 官方宿主真人验收包

本包使用未修改的官方 ViaProxy 3.4.13、官方 Geyser-ViaProxy 2.11.3 build 1247、真实 ConnectPlus 0.1.0 和独立桥接 1.0.1。
后续功能同样禁止修改宿主，见 AGENTS.md。旧补丁版包已废弃。

1. 解压到新的空目录，使用 Java 21 或更高版本运行 start.bat。
2. 等待 Geyser 完成首次 Minecraft 素材下载，并出现 registered with ConnectPlus；能力应为 verified-xuid、exact-channel-binding、targeted-disconnect。
3. 基岩客户端连接“运行机器地址:19133”（UDP），Java 正版客户端连接“运行机器地址:25571”（TCP）。跨机器测试时在系统防火墙放行对应端口。
4. 按 真人验收记录.md 从 G01/C01 开始操作并填写结果。后端切服验收需准备可用的 Java 测试服，在 ConnectPlus 大厅添加测试服书签。
5. 控制台输入 stop 关闭宿主。

同一 Xbox XUID 的两台基岩客户端重复登录：旧客户端继续在线，第二个连接被 Geyser 原生拒绝。
关联 Java 档案后的 Java/基岩互斥、书签、关联和解绑继续由 ConnectPlus 管理。

运行配置：ConnectPlus mode=lobby、geyser-support.enabled=true、allowAccountLogin=true；ViaProxy proxy-online-mode=true；Geyser auth-type=offline、validate-bedrock-login=true、use-waterdogpe-forwarding=false。
Geyser 的 offline Java 下游模式不关闭 Xbox 身份验证。请保留 Bedrock 登录验证，不安装 Floodgate 密钥。

自动化结果见 verification/startup-result.json；官方来源及 SHA-256 见 build-info.json 和 SHA256SUMS。
真人客户端验收尚未执行，自动化测试和启动检查不会代替真人验收。
"""
    (package / "使用说明.md").write_text(readme, encoding="utf-8")
    print("Official hosts checked; package assembled. Starting isolated host smoke check.", flush=True)

    smoke = ROOT / "build/verification/smoke" / uuid.uuid4().hex[:12]
    shutil.copytree(package, smoke)
    tcp, udp = free_port(socket.SOCK_STREAM), free_port(socket.SOCK_DGRAM)
    config = smoke / "viaproxy.yml"
    config.write_text(config.read_text(encoding="utf-8").replace(
        "bind-address: 0.0.0.0:25571", f"bind-address: 127.0.0.1:{tcp}"), encoding="utf-8")
    config = smoke / "plugins/Geyser/config.yml"
    config.write_text(config.read_text(encoding="utf-8").replace(
        "address: 0.0.0.0", "address: 127.0.0.1").replace("port: 19133", f"port: {udp}"), encoding="utf-8")
    log = smoke / "startup.log"
    with log.open("wb") as output:
        process = subprocess.Popen(
            ["java", "-DskipUpdateCheck", "-Dfile.encoding=UTF-8", "-jar", "viaproxy.jar", "cli"],
            cwd=smoke, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            ready = False
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline and process.poll() is None:
                output_text = log.read_text(encoding="utf-8", errors="replace")
                if ("registered with ConnectPlus (bridge capabilities:" in output_text
                        and (smoke / "plugins/Geyser/cache/client_jar.hash").is_file()):
                    ready = True
                    break
                if "bridge disabled" in output_text or "/FATAL]" in output_text:
                    raise AssertionError("Bridge failed startup checks")
                time.sleep(1)
            assert ready, "Host registration or Minecraft assets did not finish"
            output_text = re.sub(r"\x1b\[[0-9;]*m", "", log.read_text(encoding="utf-8", errors="replace"))
            errors = [line for line in output_text.splitlines()
                      if "/ERROR]" in line or "Exception" in line or "bridge disabled" in line]
            assert not errors, "\n".join(errors)
            registration = next(line for line in output_text.splitlines()
                                if "registered with ConnectPlus (bridge capabilities:" in line)
            assert all(c in registration for c in CAPABILITIES), registration
            assert "authenticated-duplicate-admission" not in registration, registration
            assert "official host mode: duplicate XUID logins keep native Geyser rejection" in output_text
            print(registration, flush=True)
            with socket.create_connection(("127.0.0.1", tcp), timeout=3):
                pass
            magic = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
            request = b"\x01" + struct.pack(">q", int(time.time() * 1000)) + magic + struct.pack(">q", 1234567)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.settimeout(5)
                client.sendto(request, ("127.0.0.1", udp))
                response, _ = client.recvfrom(4096)
            assert response[0] == 0x1c and response[17:33] == magic, "Invalid RakNet pong"
            motd = response[35:35 + struct.unpack(">H", response[33:35])[0]].decode()
            assert motd.startswith("MCPE;"), motd
            print("PASS: Bedrock UDP RakNet pong: " + motd, flush=True)
            process.communicate(b"stop\n", timeout=30)
            assert process.returncode == 0, process.returncode
        except BaseException:
            print(log.read_text(encoding="utf-8", errors="replace")[-16000:], flush=True)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=15)
    assert sha(smoke / "viaproxy.jar") == sha(via), "Host changed during execution"
    assert sha(smoke / "plugins/Geyser-ViaProxy.jar") == sha(geyser), "Geyser changed during execution"
    result = {
        "status": "PASS",
        "checked": ["official ViaProxy checksum verified", "official Geyser download checksum verified",
                    "unmodified hosts loaded", "real ConnectPlus loaded", "bridge 1.0.1 enabled",
                    "three baseline capabilities registered without duplicate-admission hook",
                    "full ConnectPlus configuration loaded without merge errors",
                    "Minecraft client assets downloaded and extracted", "Java entry accepted TCP connections",
                    "Bedrock UDP RakNet pong returned", "clean host shutdown", "host JARs unchanged after execution"],
        "bridgeTests": tests, "connectPlusTests": counts, "humanAcceptance": "NOT_EXECUTED",
        "hostPid": process.pid, "hostExitCode": process.returncode, "hostStopped": process.poll() is not None,
        "tcpPort": tcp, "udpPort": udp, "bedrockMotd": motd, "log": str(log.relative_to(ROOT)),
    }
    verification = package / "verification"
    verification.mkdir(exist_ok=True)
    shutil.copy2(log, verification / "startup.log")
    (verification / "startup-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (verification / "bridge-test-summary.json").write_text(json.dumps(tests, indent=2), encoding="utf-8")
    (verification / "connectplus-test-summary.json").write_text(json.dumps(counts, indent=2), encoding="utf-8")
    (smoke / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    info = {
        "hostPolicy": "UNMODIFIED_OFFICIAL_HOSTS_ONLY",
        "geyserVersion": GEYSER_VERSION, "geyserBuild": GEYSER_BUILD,
        "geyserCommit": metadata["changes"][0]["commit"], "geyserSource": geyser_url,
        "viaProxyVersion": VIAPROXY_VERSION, "viaProxySource": asset["browser_download_url"],
        "bridgeVersion": "1.0.1", "connectPlusVersion": "0.1.0", "jdk": subprocess.check_output(
            ["java", "-version"], stderr=subprocess.STDOUT).decode(errors="replace"),
        "capabilities": CAPABILITIES,
        "runtimeSeries": "Geyser 2.11.x >=2.11.3; ViaProxy 3.4.x >=3.4.13; Java >=21 plus read-only adapter probes",
        "duplicateXuidBehavior": "native Geyser rejection; existing client stays online",
        "humanAcceptance": "NOT_EXECUTED", "verification": result["checked"],
        "artifacts": {name: {"bytes": (package / name).stat().st_size, "sha256": sha(package / name)}
                      for name in files if name.endswith(".jar")},
        "configurationSha256": {name: sha(package / name) for name in files if name.endswith(".yml")},
    }
    (package / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    allowed = set(files) | {"使用说明.md", "build-info.json", "verification/startup.log",
                            "verification/startup-result.json", "verification/bridge-test-summary.json",
                            "verification/connectplus-test-summary.json"}
    found = {p.relative_to(package).as_posix() for p in package.rglob("*") if p.is_file()}
    assert found == allowed, ("Unexpected package contents", found - allowed, allowed - found)
    package_files = sorted(p for p in package.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    assert not any(p.name in {"secret.key", "accounts.json"} or p.suffix in {".patch", ".pem"}
                   or "mock-" in p.name or "geyser-patch" in str(p.relative_to(package)) for p in package_files), "Private or patched material"
    (package / "SHA256SUMS").write_text("\n".join(
        sha(p) + "  " + p.relative_to(package).as_posix() for p in package_files) + "\n", encoding="utf-8")
    archive = Path(shutil.make_archive(str(package), "zip", package.parent, package.name))
    with zipfile.ZipFile(archive) as zipped:
        assert zipped.testzip() is None
        for source in package.rglob("*"):
            if source.is_file():
                archived = zipped.read(package.name + "/" + source.relative_to(package).as_posix())
                assert hashlib.sha256(archived).hexdigest() == sha(source), source
    delivery = ROOT / "build/acceptance/ConnectPlus-Bedrock-Acceptance-Official"
    assert package.resolve().is_relative_to(ROOT.resolve())
    assert delivery.resolve().is_relative_to(ROOT.resolve())
    if delivery.exists():
        retired = ROOT / "build/verification/retired-packages" / uuid.uuid4().hex[:12]
        assert retired.resolve().is_relative_to(ROOT.resolve())
        retired.parent.mkdir(parents=True, exist_ok=True)
        delivery.rename(retired)
    package.rename(delivery)
    final_archive = delivery.with_suffix(".zip")
    shutil.copy2(archive, final_archive)
    archive = final_archive
    archive.with_suffix(".zip.sha256").write_text(sha(archive) + "  " + archive.name + "\n", encoding="utf-8")
    (ROOT / "build/acceptance/ConnectPlus-Bedrock-Acceptance.zip.OBSOLETE.txt").write_text(
        "旧补丁方案已废弃，请使用 ConnectPlus-Bedrock-Acceptance-Official.zip。", encoding="utf-8")
    with (ROOT / "docs/superpowers/plans/2026-10-04-official-host-bridge.md").open("a", encoding="utf-8") as ledger:
        ledger.write(f"\n- Task 2 GREEN: bridge {tests['successful']}/{tests['tests']}; ConnectPlus {counts['tests']} tests, zero failures/errors/skips.\n")
        ledger.write("- Task 3 complete: policies persisted in both AGENTS.md; protocols synchronized; official host checksum verified.\n")
        ledger.write("- Task 4 smoke PASS: official unchanged hosts, real plugins, three capabilities, assets, TCP/UDP, clean stop; human acceptance NOT_EXECUTED.\n")
    print("PASS:", archive, archive.stat().st_size, "bytes", flush=True)
    print("ZIP SHA256:", sha(archive), flush=True)


if __name__ == "__main__":
    main()
