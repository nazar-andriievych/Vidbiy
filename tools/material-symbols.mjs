// Завантажує іконки Material Symbols Rounded (Apache 2.0) і кладе їх у застосунок
// як vector drawable: res/drawable/ic_<name>.xml.
//
// Навіщо не material-icons-extended: бібліотека важить мегабайти, а нам потрібні
// два десятки іконок. Специфікація дизайну (docs/design/design-spec.md, 1.4) радить саме
// Material Symbols Rounded, outlined, weight 400.
//
//   node tools/material-symbols.mjs alarm location_on tune
//
// Іконка вже є — перезаписується. Колір задається в Compose (tint), тож у файлі чорний.

import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outDir = path.join(root, "app", "src", "main", "res", "drawable");
const names = process.argv.slice(2);

if (names.length === 0) {
  console.error("Вкажіть назви іконок: node tools/material-symbols.mjs alarm location_on");
  process.exit(1);
}

for (const name of names) {
  const url = `https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/${name}/default/24px.svg`;
  const response = await fetch(url, { headers: { "User-Agent": "vidbiy-dev (icons)" } });
  if (!response.ok) {
    console.error(`${name}: ${response.status} — такої іконки немає?`);
    process.exitCode = 1;
    continue;
  }
  const svg = await response.text();
  const viewBox = /viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"/.exec(svg);
  const paths = [...svg.matchAll(/<path[^>]*\sd="([^"]+)"/g)].map((m) => m[1]);
  if (!viewBox || paths.length === 0) {
    console.error(`${name}: не вдалося розібрати SVG`);
    process.exitCode = 1;
    continue;
  }
  const [, minX, minY, width, height] = viewBox;
  // Material Symbols малюються у viewBox "0 -960 960 960"; vector drawable не знає зсуву
  // viewBox, тож зсуваємо групою.
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Material Symbols Rounded «${name}» (Apache 2.0). Згенеровано tools/material-symbols.mjs -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="${width}"
    android:viewportHeight="${height}">
    <group android:translateX="${-Number(minX)}" android:translateY="${-Number(minY)}">
${paths.map((d) => `        <path android:fillColor="#FF000000" android:pathData="${d}" />`).join("\n")}
    </group>
</vector>
`;
  const file = path.join(outDir, `ic_${name}.xml`);
  await writeFile(file, xml);
  console.log(`ic_${name}.xml`);
}
