package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;

/**
 * Wiki 页面 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPageEntity> {

    /**
     * DB 级别关键词搜索（H2 + MySQL 通用 LIKE）。
     * 不 SELECT content CLOB，避免全量加载到 Java 内存。
     */
    @Select("SELECT id, kb_id, slug, title, summary, source_raw_ids, last_updated_by " +
            "FROM mate_wiki_page " +
            "WHERE kb_id = #{kbId} AND deleted = 0 " +
            "AND (LOWER(title) LIKE #{pattern} OR LOWER(summary) LIKE #{pattern} " +
            "     OR LOWER(content) LIKE #{pattern}) " +
            "ORDER BY title LIMIT 20")
    List<WikiPageEntity> searchByKeyword(@Param("kbId") Long kbId, @Param("pattern") String pattern);
}
