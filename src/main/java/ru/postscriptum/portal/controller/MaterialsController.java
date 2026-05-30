package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialsController {

    private final JdbcTemplate jdbc;

    // ── GET /api/materials — all materials grouped by course ────────────────
    @GetMapping
    public ResponseEntity<?> list() {
        String sql = """
            SELECT cm.id, cm.title, cm.type, cm.url,
                   c.title AS course_title,
                   COALESCE(lang.code,'fr') AS lang
            FROM course_materials cm
            JOIN course_modules mod ON mod.id = cm.module_id
            JOIN courses c ON c.id = mod.course_id
            LEFT JOIN languages lang ON lang.id = c.language_id
            ORDER BY c.title, cm.position
            """;

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",     row.get("id"));
            item.put("title",  row.get("title"));
            item.put("type",   row.get("type"));
            item.put("url",    row.get("url"));
            item.put("course", row.get("course_title"));
            item.put("lang",   row.get("lang"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    // ── POST /api/materials — teacher adds a material ───────────────────────
    @PostMapping
    public ResponseEntity<?> create(Authentication auth,
                                    @RequestBody Map<String, Object> body) {
        Long me = jdbc.queryForObject(
            "SELECT id FROM users WHERE email=?", Long.class, auth.getName());

        Object moduleIdRaw = body.get("moduleId");
        if (moduleIdRaw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "moduleId is required"));
        }
        Long moduleId = ((Number) moduleIdRaw).longValue();
        String title = (String) body.get("title");
        String type  = (String) body.get("type");
        String url   = (String) body.get("url");

        Long newId = jdbc.queryForObject("""
            INSERT INTO course_materials (module_id, title, type, url, created_by, created_at, position)
            VALUES (?, ?, ?::material_type, ?,
                    ?,
                    NOW(),
                    COALESCE((SELECT MAX(position)+1 FROM course_materials WHERE module_id=?), 1))
            RETURNING id
            """, Long.class,
            moduleId, title, type, url, me, moduleId);

        return ResponseEntity.ok(Map.of("id", newId));
    }
}
