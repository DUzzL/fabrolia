"""
Zero-dependency .xlsx writer for a spark call-tree "pyramid" (icicle / flame graph
rendered as a real merged-cell spreadsheet — exactly the layout from the user's
reference: a wide top cell for the biggest method, splitting downward into
proportionally-sized child cells until the smallest leaf.

An .xlsx is a zip of XML parts; we emit only what we need:
  [Content_Types].xml, _rels/.rels, xl/workbook.xml, xl/_rels/workbook.xml.rels,
  xl/styles.xml, xl/worksheets/sheet1.xml
Strings are inline (no sharedStrings). Each method = one merged range whose
anchor cell carries the fill (by owning mod) + border + label; the merged range
shows that formatting across its whole width. Cell width ∝ time.
"""
import os
import sys
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "gen"))
from core import Analysis, load, short_label  # noqa: E402


# light fill palette for mods (black text stays readable); buckets get fixed hues
BUCKET_COLORS = {
    "(native)": "D9D9D9",
    "(jdk)": "DCE6F1",
    "minecraft": "E2EFDA",
}
UNATTRIBUTED_COLOR = "FFF2CC"
MOD_PALETTE = [
    "F8CBAD", "BDD7EE", "C6E0B4", "FFE699", "D9C2E9", "B4C6E7", "F4B9C2",
    "C9E4CA", "FCE1B6", "C5D9F1", "E6B8AF", "B6D7A8", "FFD966", "D5A6BD",
    "A2C4C9", "EAD1DC", "D0E0E3", "F9CB9C", "B7B7B7", "9FC5E8",
]


def color_for(owner, cache):
    if owner in BUCKET_COLORS:
        return BUCKET_COLORS[owner]
    if owner.startswith("(unattributed"):
        return UNATTRIBUTED_COLOR
    if owner not in cache:
        cache[owner] = MOD_PALETTE[len(cache) % len(MOD_PALETTE)]
    return cache[owner]


def col_letter(n):  # 1-based -> A, B, ... AA ...
    s = ""
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def xesc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def alloc(total_w, weights):
    s = sum(weights)
    if s <= 0 or total_w <= 0:
        return [0] * len(weights)
    raw = [total_w * wt / s for wt in weights]
    base = [int(x) for x in raw]
    rem = total_w - sum(base)
    order = sorted(range(len(weights)), key=lambda i: raw[i] - base[i], reverse=True)
    for i in range(max(0, rem)):
        base[order[i % len(order)]] += 1
    return base


def build_rows(a, ta, ncols, max_depth):
    """Return (rows, owners_seen, stats). rows[d] = list of (c0, width, owner, label)."""
    def kids_sorted(refs):
        return sorted(refs, key=lambda r: ta.total[r], reverse=True)

    owners = {"minecraft": True}  # header row colour
    rows = [[(0, ncols, "minecraft", f"Thread: {ta.name}  —  100%  —  total {a.fmt(ta.grand_total)}  —  {len(ta.pool):,} nodes")]]
    segments = [(0, ncols, ta.grand_total, kids_sorted(ta.root_refs))]
    drawn = 0
    depth = 0
    while segments and depth < max_depth:
        rowcells = []
        nxt = []
        for (start, width, ntot, kids) in segments:
            if not kids or ntot <= 0 or width <= 0:
                continue
            ctot = [ta.total[r] for r in kids]
            csum = sum(ctot)
            cw = min(width, round(width * csum / ntot))
            for r, w in zip(kids, alloc(cw, ctot)):
                if w <= 0:
                    continue
                nd = ta.pool[r]
                owner = a.owner_of(nd)
                owners[owner] = True
                pct = 100.0 * ta.total[r] / ta.grand_total if ta.grand_total else 0
                lbl = f"{short_label(nd)}  {pct:.2f}%  ({a.fmt(ta.total[r])})"
                rowcells.append((start, w, owner, lbl))
                nxt.append((start, w, ta.total[r], kids_sorted(nd.children_refs)))
                start += w
                drawn += 1
        if rowcells:
            rows.append(rowcells)
        segments = nxt
        depth += 1
    return rows, list(owners.keys()), {"drawn": drawn, "depth": len(rows)}


# --------------------------------------------------------------------------- #
# OOXML parts
# --------------------------------------------------------------------------- #
CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Call pyramid" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""


def build_styles(owner_colors):
    """owner_colors: list of (owner, RRGGBB) in xf order starting at xf index 1."""
    fills = ['<fill><patternFill patternType="none"/></fill>',
             '<fill><patternFill patternType="gray125"/></fill>']
    xfs = ['<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>']
    for i, (_owner, rgb) in enumerate(owner_colors):
        fills.append(f'<fill><patternFill patternType="solid"><fgColor rgb="FF{rgb}"/><bgColor indexed="64"/></patternFill></fill>')
        fillid = 2 + i
        xfs.append(f'<xf numFmtId="0" fontId="0" fillId="{fillid}" borderId="1" xfId="0" '
                   f'applyFill="1" applyBorder="1" applyAlignment="1">'
                   f'<alignment horizontal="left" vertical="center" indent="0"/></xf>')
    styles = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<fonts count="1"><font><sz val="9"/><name val="Calibri"/></font></fonts>'
        f'<fills count="{len(fills)}">' + "".join(fills) + '</fills>'
        '<borders count="2">'
        '<border><left/><right/><top/><bottom/><diagonal/></border>'
        '<border><left style="thin"><color rgb="FFBFBFBF"/></left>'
        '<right style="thin"><color rgb="FFBFBFBF"/></right>'
        '<top style="thin"><color rgb="FFBFBFBF"/></top>'
        '<bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border>'
        '</borders>'
        '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        f'<cellXfs count="{len(xfs)}">' + "".join(xfs) + '</cellXfs>'
        '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
        '</styleSheet>'
    )
    return styles


def build_sheet(rows, ncols, owner_xf):
    """owner_xf: dict owner -> xf index (>=1)."""
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
           '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
           f'<dimension ref="A1:{col_letter(ncols)}{len(rows)}"/>',
           '<sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews>',
           '<sheetFormatPr defaultRowHeight="14"/>',
           f'<cols><col min="1" max="{ncols}" width="2.6" customWidth="1"/></cols>',
           '<sheetData>']
    merges = []
    for ri, cells in enumerate(rows, start=1):
        row_xml = [f'<row r="{ri}">']
        for (c0, w, owner, label) in cells:
            col = col_letter(c0 + 1)
            xf = owner_xf[owner]
            ref = f"{col}{ri}"
            row_xml.append(f'<c r="{ref}" s="{xf}" t="inlineStr"><is><t xml:space="preserve">{xesc(label)}</t></is></c>')
            if w > 1:
                merges.append(f'<mergeCell ref="{col}{ri}:{col_letter(c0 + w)}{ri}"/>')
        row_xml.append('</row>')
        out.append("".join(row_xml))
    out.append('</sheetData>')
    if merges:
        out.append(f'<mergeCells count="{len(merges)}">' + "".join(merges) + '</mergeCells>')
    out.append('</worksheet>')
    return "".join(out)


def write_pyramid_xlsx(a, ta, path, ncols=600, max_depth=60):
    rows, owners, stats = build_rows(a, ta, ncols, max_depth)
    cache = {}
    owner_colors = [(o, color_for(o, cache)) for o in owners]
    owner_xf = {o: i + 1 for i, (o, _) in enumerate(owner_colors)}
    styles = build_styles(owner_colors)
    sheet = build_sheet(rows, ncols, owner_xf)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", ROOT_RELS)
        z.writestr("xl/workbook.xml", WORKBOOK)
        z.writestr("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        z.writestr("xl/styles.xml", styles)
        z.writestr("xl/worksheets/sheet1.xml", sheet)
    return stats


if __name__ == "__main__":
    path = sys.argv[1]
    ncols = int(sys.argv[2]) if len(sys.argv) > 2 else 600
    a = Analysis(load(path), path)
    base = os.path.splitext(os.path.basename(path))[0]
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
    os.makedirs(out_dir, exist_ok=True)
    multi = len(a.threads) > 1
    for ta in a.threads:
        safe = "".join(c if c.isalnum() else "_" for c in ta.name).strip("_")
        out = os.path.join(out_dir, base + (f".{safe}" if multi else "") + ".pyramid.xlsx")
        stats = write_pyramid_xlsx(a, ta, out, ncols=ncols)
        print(f"wrote {out}  (drawn={stats['drawn']} cells, depth={stats['depth']} rows, ncols={ncols})")
