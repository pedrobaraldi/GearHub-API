// Roda o Maven Wrapper certo para o sistema: mvnw.cmd no Windows, mvnw nos demais.
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const windows = process.platform === "win32";
const wrapper = join(root, windows ? "mvnw.cmd" : "mvnw");

// No Windows, arquivos .cmd só rodam pelo shell; as aspas cobrem pastas com espaço
const child = windows
  ? spawn(`"${wrapper}"`, process.argv.slice(2), { cwd: root, stdio: "inherit", shell: true })
  : spawn(wrapper, process.argv.slice(2), { cwd: root, stdio: "inherit" });

child.on("exit", (code, signal) => process.exit(signal ? 1 : code ?? 0));
