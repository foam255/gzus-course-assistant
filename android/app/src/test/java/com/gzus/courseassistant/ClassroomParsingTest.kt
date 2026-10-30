package com.gzus.courseassistant

import com.gzus.courseassistant.data.network.JwxtParsers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomParsingTest {
    @Test
    fun parsesEmptyClassroomItems() {
        val payload = JSONObject(
            """
            {
              "totalResult": "2",
              "items": [
                {
                  "cd_id": "1",
                  "cdbh": "S401",
                  "cdmc": "S401",
                  "cdjc": "电子设计实践实习基地(38)",
                  "jxlmc": "笃行楼S",
                  "cdlbmc": "广-电子信息工程专业实验室",
                  "xqmc": "广州校区",
                  "zws": "38"
                },
                {
                  "cd_id": "2",
                  "cdbh": "L102",
                  "cdmc": "L102",
                  "cdjc": "声光影视系统集成实验室(250人)",
                  "jxlmc": "公共场所",
                  "cdlbmc": "广-声光影视系统集成实验室(250)",
                  "xqmc": "广州校区",
                  "zws": "250"
                }
              ]
            }
            """.trimIndent(),
        )

        val classrooms = JwxtParsers.parseClassrooms(payload)

        assertEquals(2, classrooms.size)
        val first = classrooms.first { it.code == "S401" }
        assertEquals("电子设计实践实习基地(38)", first.name)
        assertEquals("笃行楼S", first.building)
        assertEquals("广-电子信息工程专业实验室", first.category)
        assertEquals("广州校区", first.campus)
        assertEquals("38", first.seats)
        assertEquals("1", first.id)
    }

    @Test
    fun skipsRowsWithoutClassroomCode() {
        val payload = JSONObject(
            """{"items":[{"cd_id":"1","cdbh":""},{"cd_id":"2","cdbh":"A101"}]}""",
        )

        val classrooms = JwxtParsers.parseClassrooms(payload)

        assertEquals(1, classrooms.size)
        assertEquals("A101", classrooms.first().code)
    }

    @Test
    fun sortsByCampusBuildingAndCode() {
        val payload = JSONObject(
            """
            {"items":[
              {"cdbh":"B203","xqmc":"江门校区","jxlmc":"S实验楼(江)"},
              {"cdbh":"A101","xqmc":"广州校区","jxlmc":"2号H4教学楼"},
              {"cdbh":"A102","xqmc":"广州校区","jxlmc":"2号H4教学楼"},
              {"cdbh":"S401","xqmc":"广州校区","jxlmc":"1号H1教学楼"}
            ]}
            """.trimIndent(),
        )

        val classrooms = JwxtParsers.parseClassrooms(payload)

        assertEquals(
            listOf("S401", "A101", "A102", "B203"),
            classrooms.map { it.code },
        )
    }

    @Test
    fun returnsEmptyWhenPayloadHasNoItems() {
        assertTrue(JwxtParsers.parseClassrooms(JSONObject("""{"totalResult":"0"}""")).isEmpty())
    }
}
