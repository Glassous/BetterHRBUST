package com.glassous.betterhrbust.core.parser

import com.glassous.betterhrbust.core.model.ProcessCampus
import com.glassous.betterhrbust.core.model.ProcessClass
import com.glassous.betterhrbust.core.model.ProcessSemester
import com.glassous.betterhrbust.core.model.ProcessWeek
import com.glassous.betterhrbust.core.model.TeachingProcessData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析随应用打包的本科教学进程快照（`assets/teaching-process.json`）。
 *
 * 结构与 `shared/teaching-process.json` 完全一致；这里只做结构映射，
 * 字形解释与阶段合并交给工具层，避免数据文件里固化业务语义。
 */
fun parseTeachingProcess(json: String): TeachingProcessData {
    val root = JSONObject(json)
    return TeachingProcessData(
        schemaVersion = root.optInt("schemaVersion", 1),
        source = root.optString("source"),
        sourceUpdatedAt = root.optString("sourceUpdatedAt"),
        updatedAt = root.optString("updatedAt"),
        patterns = root.stringList("patterns"),
        semesters = root.objectList("semesters").map(::parseSemester),
        warnings = root.stringList("warnings")
    )
}

private fun parseSemester(json: JSONObject): ProcessSemester = ProcessSemester(
    id = json.optString("id"),
    label = json.optString("label"),
    academicYear = json.optString("academicYear"),
    term = json.optInt("term", 1),
    weeks = json.objectList("weeks").map { week ->
        ProcessWeek(
            week = week.optInt("w"),
            monday = week.optString("mon"),
            sunday = week.optString("sun")
        )
    },
    legend = json.stringList("legend"),
    campuses = json.objectList("campuses").map { campus ->
        ProcessCampus(
            name = campus.optString("name"),
            classes = campus.tupleList("classes").mapNotNull { entry ->
                if (entry.length() < 3) return@mapNotNull null
                ProcessClass(
                    name = entry.optString(0),
                    hours = entry.optInt(1, 0),
                    patternIndex = entry.optInt(2, 0)
                )
            }
        )
    }
)

private fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).map { array.optString(it, "") }
}

private fun JSONObject.objectList(name: String): List<JSONObject> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** 班级条目是 `[名字, 周学时, patterns 索引]` 三元数组，不能按对象解析。 */
private fun JSONObject.tupleList(name: String): List<JSONArray> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONArray(it) }
}
