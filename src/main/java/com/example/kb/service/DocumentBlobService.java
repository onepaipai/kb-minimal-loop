package com.example.kb.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 原始文件字节存储：上传时写入，供下载原格式；与文档版本同生命周期。
 * 可用 app.blob.enabled 关闭、app.blob.max-size-mb 限制单文件体积，超限或关闭时跳过保存（不阻断入库）。
 */
@Service
public class DocumentBlobService {

    private static final Logger log = LoggerFactory.getLogger(DocumentBlobService.class);

    /** 下载原件：文件名、内容类型、字节；不存在返回 null。 */
    public record Blob(String filename, String contentType, byte[] bytes) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final long maxBytes;

    public DocumentBlobService(JdbcTemplate jdbcTemplate,
                               @Value("${app.blob.enabled:true}") boolean enabled,
                               @Value("${app.blob.max-size-mb:10}") int maxSizeMb) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.maxBytes = (long) maxSizeMb * 1024 * 1024;
    }

    /** 写入某版本原件：关闭或超限时跳过；已存在则覆盖。 */
    public void save(String source, int version, String filename, String contentType, byte[] bytes) {
        if (!enabled) {
            return;
        }
        if (bytes.length > maxBytes) {
            log.warn("原件超过上限跳过保存 source={} size={} maxBytes={}", source, bytes.length, maxBytes);
            return;
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO document_blob(source, version, filename, content_type, file_size, bytes) "
                            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (source, version) DO UPDATE SET "
                            + "filename = EXCLUDED.filename, content_type = EXCLUDED.content_type, "
                            + "file_size = EXCLUDED.file_size, bytes = EXCLUDED.bytes, created_at = now()");
            ps.setString(1, source);
            ps.setInt(2, version);
            ps.setString(3, filename);
            ps.setString(4, contentType);
            ps.setInt(5, bytes.length);
            ps.setBytes(6, bytes);
            return ps;
        });
    }

    /** 读原件：不存在返回 null。 */
    public Blob find(String source, int version) {
        List<Blob> rows = jdbcTemplate.query(
                "SELECT filename, content_type, bytes FROM document_blob WHERE source = ? AND version = ?",
                (ResultSet rs, int rowNum) -> new Blob(rs.getString("filename"),
                        rs.getString("content_type"), rs.getBytes("bytes")), source, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 某篇文档是否有原件（清单展示下载按钮用）。 */
    public boolean exists(String source) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_blob WHERE source = ?", Integer.class, source);
        return count != null && count > 0;
    }

    /** 删某版本原件（旧版清理与回滚时用）。 */
    public void deleteVersion(String source, int version) {
        jdbcTemplate.update("DELETE FROM document_blob WHERE source = ? AND version = ?", source, version);
    }

    /** 删某篇文档全部原件（删文档时用）。 */
    public void deleteBySource(String source) {
        jdbcTemplate.update("DELETE FROM document_blob WHERE source = ?", source);
    }

    /** 清理旧版原件：只清理版本事实表中明确标记为旧版且超出保留数的原件。 */
    public void purgeOld(int keep) {
        jdbcTemplate.update("DELETE FROM document_blob b WHERE (b.source, b.version) IN ("
                + "SELECT source, version FROM (SELECT dv.source, dv.version, row_number() OVER "
                + "(PARTITION BY dv.source ORDER BY dv.version DESC) AS rn FROM document_version dv "
                + "JOIN document d ON d.source = dv.source "
                + "WHERE dv.lifecycle_state = 'SUPERSEDED' AND d.deleted_at IS NULL) ranked WHERE rn > ?)", keep);
    }
}
