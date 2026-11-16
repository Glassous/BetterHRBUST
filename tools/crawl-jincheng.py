"""收录教务处公开的本科教学进程表，生成结构化的学期进程快照。

原站是 WPS 导出的 frameset 页面：jincheng.htm 索引 33 个学期标签，
每个 sheet 里再按校区排列「班级 × 教学周」的符号网格。本脚本只做结构抽取，
符号原样保留（含义由前端 web/src/utils/teachingProcessSymbols.js 解释）。
"""
import argparse
import json
import re
import time
from datetime import date, timedelta
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin
from urllib.request import ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://jwzx.hrbust.edu.cn/homepage/downloads/"
SOURCE = BASE + "jincheng.htm"
TARGET = ROOT / "shared" / "teaching-process.json"
# Android 页面离线读取 assets 内的同一份快照（与 resources.json 的做法一致）
ANDROID_TARGET = ROOT / "android" / "app" / "src" / "main" / "assets" / "teaching-process.json"
OPENER = build_opener(ProxyHandler({}))
USER_AGENT = "BetterHRBUST-TeachingProcess/1.0"
TITLE_MARK = "本科教学进程表"
LEGEND_MARK = "符号说明"
TERM_NAMES = {"一": 1, "二": 2}
WEEK_LABELS = "一二三四五六七八九十"
WEEK_LABEL_RE = re.compile(r"[一二三四五六七八九十廿]{1,3}")
DAY_RE = re.compile(r"^(\d{1,2})(?:/(\d{1,2}))?$")
CAMPUS_RE = re.compile(r"[（(]\s*([东南西北])\s*区\s*[）)]")
YEAR_RE = re.compile(r"(\d{4})\s*-{2,}\s*(\d{4})\s*学年\s*第\s*([一二])\s*学期")
TAB_RE = re.compile(r"(\d{2})\s*-\s*(\d{2})\s*[（(]\s*([12])\s*[）)]")
SPAN_RE = re.compile(r"<x:Name>(.*?)</x:Name>\s*<x:WorksheetSource[^>]*HRef=\"([^\"]+)\"", re.S | re.I)
SHLINK_RE = re.compile(r"<link\s+id=\"shLink\"\s+href=\"([^\"]+)\"", re.I)
SAVED_RE = re.compile(r"<o:LastSaved>([^<]+)</o:LastSaved>", re.I)


def clean_text(value):
    """去掉全部空白（含 nbsp 与全角空格），让「安 全26 (1-2)」变成可搜索的班级名。"""
    return re.sub(r"[\s\u00a0\u3000]+", "", value or "")


def fetch(url):
    time.sleep(0.15)
    with OPENER.open(Request(url, headers={"User-Agent": USER_AGENT}), timeout=30) as response:
        data = response.read()
        charset = response.headers.get_content_charset() or "utf-8"
        for candidate in dict.fromkeys([charset, "utf-8", "gb18030"]):
            try:
                return data.decode(candidate, errors="strict"), response.headers
            except UnicodeDecodeError:
                continue
        raise ValueError(f"页面编码无法识别：{url}")


class RowParser(HTMLParser):
    """把表格拆成 [[(文本, colspan), ...], ...]，跳过脚本与样式。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows = []
        self._row = None
        self._cell = None
        self._span = 1
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._skip += 1
            return
        if self._skip:
            return
        if tag == "tr":
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []
            span = dict(attrs).get("colspan") or "1"
            self._span = int(span) if span.isdigit() else 1
        elif tag == "br" and self._cell is not None:
            self._cell.append(" ")

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            if self._skip:
                self._skip -= 1
            return
        if self._skip:
            return
        if tag in ("td", "th") and self._cell is not None:
            if self._row is not None:
                self._row.append((clean_text("".join(self._cell)), max(1, min(self._span, 40))))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            self.rows.append(self._row)
            self._row = None

    def handle_data(self, data):
        if not self._skip and self._cell is not None:
            self._cell.append(data)


def expand(cells):
    """按 colspan 展开为逻辑列，便于统一按列切片。"""
    out = []
    for text, span in cells:
        out.extend([text] * span)
    return out


def sheet_tabs(index_html):
    tabs = [(clean_text(name), href) for name, href in SPAN_RE.findall(index_html)]
    if len(tabs) >= 8:
        return tabs
    # 兜底：条件注释被剥离时只剩 shLink 顺序，标签名由各 sheet 标题补全
    return [("", href) for href in SHLINK_RE.findall(index_html)]


def source_updated_at(index_html, headers):
    saved = SAVED_RE.search(index_html)
    if saved:
        return saved.group(1)[:10]
    modified = headers.get("Last-Modified") if headers is not None else None
    if modified:
        try:
            return parsedate_to_datetime(modified).date().isoformat()
        except (TypeError, ValueError):
            pass
    return ""


def academic_year_of(title, tab_label):
    matched = YEAR_RE.search(title)
    if matched:
        return f"{matched.group(1)}-{matched.group(2)}", TERM_NAMES[matched.group(3)]
    matched = TAB_RE.search(tab_label or "")
    if matched:
        start = int(matched.group(1))
        start = 2000 + start if start < 90 else 1900 + start
        return f"{start}-{start + 1}", int(matched.group(3))
    raise ValueError(f"无法识别学年学期：{title[:40]}")


def campus_of(title):
    matched = CAMPUS_RE.search(title)
    return matched.group(1) + "区" if matched else "校本部"


def build_dates(tokens, first_year, term, month_first):
    """把「3/2」这类写法按指定顺序展开成完整日期。

    第一学期的 sheet 写成「日/月」（31/8 = 8 月 31 日），第二学期写成
    「月/日」（3/2 = 3 月 2 日）；无斜杠的沿用当前月并自动进位。
    """
    days = []
    for token in tokens:
        matched = DAY_RE.match(token or "")
        if not matched:
            break
        days.append((int(matched.group(1)), int(matched.group(2)) if matched.group(2) else None))
    if not days:
        return []

    head = days[0]
    if head[1] is not None:
        month = head[0] if month_first else head[1]
    else:
        month = 8 if term == 1 else 2
    if not 1 <= month <= 12:
        raise ValueError(f"月取值越界：{month}")
    year = first_year + (1 if month <= 6 else 0)

    prev = None
    prev_month = month
    dates = []
    for left, right in days:
        if right is not None:
            month, day = (left, right) if month_first else (right, left)
            if month < prev_month:
                year += 1
        else:
            day = left
            if prev is not None and day <= prev:
                month += 1
                if month > 12:
                    month = 1
                    year += 1
        if not 1 <= month <= 12:
            raise ValueError(f"月取值越界：{month}")
        dates.append(date(year, month, day))
        prev, prev_month = day, month
    return dates


def parse_monday_dates(tokens, first_year, term, warnings):
    """两种写法都试一遍，取「全部是周一且相邻恰好 7 天」的那个解释。"""
    candidates = []
    for month_first in (False, True):
        try:
            dates = build_dates(tokens, first_year, term, month_first)
        except ValueError:
            continue
        if not dates:
            continue
        weekdays = all(day.weekday() == 0 for day in dates)
        spacing = all((later - earlier).days == 7 for earlier, later in zip(dates, dates[1:]))
        plausible = dates[0].month in ((7, 8, 9, 10) if term == 1 else (1, 2, 3, 4))
        candidates.append((weekdays, spacing, plausible, dates))
    if not candidates:
        raise ValueError(f"日期列无法解析：{tokens[:4]}")
    candidates.sort(key=lambda item: (item[0], item[1], item[2]), reverse=True)
    weekdays, spacing, plausible, dates = candidates[0]
    if not (weekdays and spacing):
        warnings.append(f"{dates[0].isoformat()} 起日期不是逐周周一，请人工核对")
    if not plausible:
        warnings.append(f"{dates[0].isoformat()} 起始月份与学期不符，请人工核对")
    return dates


def parse_block(block, tab_label, warnings):
    head = block[0] if block else []
    # 标题偶尔被拆到相邻单元格里，单格匹配不到时拼起来再匹配
    title = next((cell for cell in head if TITLE_MARK in cell), "") or "".join(head)
    academic_year, term = academic_year_of(title, tab_label)
    campus = campus_of(title)

    date_row = next((row for row in block if len(row) > 1 and row[0] == "月" and row[1] == "理"), None)
    label_row = next((row for row in block if "班级" in row[:-1] and row[row.index("班级") + 1] == "周"), None)
    if date_row is None or label_row is None:
        raise ValueError(f"{title[:30]} 缺少表头行")
    base = label_row.index("班级")
    date_base = date_row.index("月")

    dates = parse_monday_dates(date_row[date_base + 2:], int(academic_year[:4]), term, warnings)
    week_count = len(dates)
    if not 20 <= week_count <= 28:
        raise ValueError(f"{title[:30]} 周列数异常：{week_count}")

    labelled = sum(1 for cell in label_row[base + 2:] if WEEK_LABEL_RE.fullmatch(cell or ""))
    if labelled and labelled != week_count:
        warnings.append(f"{title[:24]} 日期列 {week_count} 与周标签 {labelled} 不一致，按日期列截断")

    weeks = [{"w": index + 1, "mon": day.isoformat(), "sun": (day + timedelta(days=6)).isoformat()}
             for index, day in enumerate(dates)]

    start = block.index(label_row) + 1
    classes = []
    legend = []

    def collapse(row):
        # 图例行常带 colspan，展开后同一段文字会重复 N 次
        return " ".join(dict.fromkeys(cell for cell in row if cell))

    for row in block[start:]:
        if len(row) <= base + 1:
            continue
        name = row[base]
        if LEGEND_MARK in name:
            if collapse(row) not in legend:
                legend.append(collapse(row))
            continue
        if legend:
            if len(legend) >= 3:
                break
            text = collapse(row)
            if text and text not in legend and not text.startswith("哈尔滨理工大学"):
                legend.append(text)
            continue
        if not re.fullmatch(r"\d{1,2}", row[base + 1] or ""):
            continue
        cells = (row[base + 2:base + 2 + week_count] + [""] * week_count)[:week_count]
        for cell in cells:
            if "|" in cell:
                raise ValueError(f"单元格包含分隔符：{name} {cell!r}")
        classes.append((name, int(row[base + 1]), cells))

    if not classes:
        raise ValueError(f"{title[:30]} 没有任何班级")
    return {"campus": campus, "academicYear": academic_year, "term": term,
            "label": tab_label or f"{academic_year[2:4]}{academic_year[7:9]}（{term}）学期",
            "weeks": weeks, "classes": classes, "legend": legend}


def parse_sheet(sheet_html, tab_label, warnings):
    parser = RowParser()
    parser.feed(sheet_html)
    rows = [expand(row) for row in parser.rows]
    titles = [index for index, row in enumerate(rows) if any(TITLE_MARK in cell for cell in row)]
    if not titles:
        raise ValueError(f"{tab_label or '未知学期'} 缺少教学进程表标题")

    blocks = []
    for order, start in enumerate(titles):
        end = titles[order + 1] if order + 1 < len(titles) else len(rows)
        warnings_local = []
        blocks.append(parse_block(rows[start:end], tab_label, warnings_local))
        warnings.extend(warnings_local)
    return blocks


def merge_campuses(blocks, warnings):
    order, merged = [], {}
    for block in blocks:
        name = block["campus"]
        if name not in merged:
            merged[name] = {"name": name, "classes": []}
            order.append(name)
        merged[name]["classes"].extend(block["classes"])
    for name in order:
        seen = {}
        unique = []
        for item in merged[name]["classes"]:
            if item[0] in seen:
                warnings.append(f"{name} 重复班级 {item[0]}，保留首个")
                continue
            seen[item[0]] = True
            unique.append(item)
        merged[name]["classes"] = unique
    return [merged[name] for name in order]


def crawl(limit):
    index_html, headers = fetch(SOURCE)
    tabs = sheet_tabs(index_html)
    if len(tabs) < 8:
        raise ValueError(f"学期标签不足：{len(tabs)}")

    patterns, pattern_id = [], {}
    semesters = []
    for name, href in tabs:
        sheet_html, _ = fetch(urljoin(SOURCE, href))
        warnings = []
        blocks = parse_sheet(sheet_html, name, warnings)
        header = blocks[0]
        classes_by_campus = merge_campuses(blocks, warnings)
        semester_classes = []
        for campus in classes_by_campus:
            encoded = []
            for class_name, hours, cells in campus["classes"]:
                key = "\x00".join(cells)
                if key not in pattern_id:
                    pattern_id[key] = len(patterns)
                    patterns.append("|" + "|".join(cells))
                encoded.append([class_name, hours, pattern_id[key]])
            semester_classes.append({"name": campus["name"], "classes": encoded})

        semesters.append({
            "id": f"{header['academicYear']}-{header['term']}",
            "label": header["label"],
            "academicYear": header["academicYear"],
            "term": header["term"],
            "weeks": header["weeks"],
            "legend": next((block["legend"] for block in blocks if block.get("legend")), []),
            "campuses": semester_classes,
            "warnings": warnings
        })
        total = sum(len(campus["classes"]) for campus in semester_classes)
        print(f"学期 {header['label']}: {len(semester_classes)} 校区 / {total} 个班 / {len(header['weeks'])} 周", flush=True)

    semesters.sort(key=lambda item: (int(item["academicYear"][:4]), item["term"]), reverse=True)
    dropped = 0
    if not limit:
        limit = len(semesters)
    if len(semesters) > limit:
        dropped = len(semesters) - limit
        semesters = semesters[:limit]

    used = {index for semester in semesters
            for campus in semester["campuses"] for _, _, index in campus["classes"]}
    remap = {old: new for new, old in enumerate(sorted(used))}
    for semester in semesters:
        for campus in semester["campuses"]:
            campus["classes"] = [[name, hours, remap[index]] for name, hours, index in campus["classes"]]
    patterns = [patterns[old] for old in sorted(used)]

    class_count = sum(len(campus["classes"]) for semester in semesters for campus in semester["campuses"])
    if class_count < 400:
        raise ValueError(f"班级数量异常：{class_count}")
    for semester in semesters:
        if not semester["campuses"] or not any(campus["classes"] for campus in semester["campuses"]):
            raise ValueError(f"{semester['label']} 没有班级")
        for campus in semester["campuses"]:
            for name, _, index in campus["classes"]:
                if not name or index >= len(patterns):
                    raise ValueError(f"{semester['label']} 班级数据异常：{name}")
    for semester in semesters:
        total_weeks = len(semester["weeks"])
        for campus in semester["campuses"]:
            for name, _, index in campus["classes"]:
                if len(patterns[index][1:].split("|")) != total_weeks:
                    raise ValueError(f"{semester['label']} {name} 图案长度与周数不一致")
        for expected, week in enumerate(semester["weeks"], start=1):
            if week["w"] != expected:
                raise ValueError("周次编号不连续")
        for earlier, later in zip(semester["weeks"], semester["weeks"][1:]):
            gap = date.fromisoformat(later["mon"]) - date.fromisoformat(earlier["mon"])
            if gap != timedelta(days=7):
                raise ValueError(f"{semester['label']} 第 {later['w']} 周日期不连续")

    warnings = []
    for semester in semesters:
        warnings.extend(f"{semester['label']}：{item}" for item in semester.pop("warnings", []))
    warnings = list(dict.fromkeys(warnings))

    return {
        "schemaVersion": 1,
        "source": SOURCE,
        "sourceUpdatedAt": source_updated_at(index_html, headers),
        "updatedAt": date.today().isoformat(),
        "patterns": patterns,
        "semesters": semesters,
        "warnings": warnings
    }, dropped, class_count


def write_if_changed(data):
    content = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    changed = False
    for target in (TARGET, ANDROID_TARGET):
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            previous = json.loads(target.read_text(encoding="utf-8"))
            if {**previous, "updatedAt": ""} == {**data, "updatedAt": ""}:
                print(f"内容无变化，保留既有快照：{target.relative_to(ROOT)}", flush=True)
                continue
        target.write_text(content, encoding="utf-8")
        changed = True
    return changed


def main():
    parser = argparse.ArgumentParser(description="抓取本科教学进程表并生成结构化快照")
    parser.add_argument("--limit", type=int, default=8, help="保留最近多少个学期（默认 8）")
    parser.add_argument("--all", action="store_true", help="保留全部学期")
    args = parser.parse_args()

    data, dropped, class_count = crawl(0 if args.all else max(1, args.limit))
    if not write_if_changed(data):
        return
    size = TARGET.stat().st_size
    print(f"完成：{len(data['semesters'])} 个学期（省略 {dropped} 个）/ {class_count} 个班级 / "
          f"{len(data['patterns'])} 个进程图案 / {size / 1024:.0f}KB", flush=True)
    print(f"原站更新：{data['sourceUpdatedAt'] or '未标注'}", flush=True)
    if data["warnings"]:
        print(f"告警 {len(data['warnings'])} 条（已写入 JSON）：", flush=True)
        for warning in data["warnings"][:10]:
            print("  - " + warning, flush=True)


if __name__ == "__main__":
    main()
