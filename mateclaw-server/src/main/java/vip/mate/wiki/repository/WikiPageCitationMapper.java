package vip.mate.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import vip.mate.wiki.dto.ChunkPageRef;
import vip.mate.wiki.dto.PageCitationWithRaw;
import vip.mate.wiki.model.WikiPageCitationEntity;

import java.util.Collection;
import java.util.List;

/**
 * RFC-029: Wiki page citation mapper — bidirectional page↔chunk queries.
 */
@Mapper
public interface WikiPageCitationMapper extends BaseMapper<WikiPageCitationEntity> {

    @Select("SELECT c.id, c.page_id, c.chunk_id, wc.raw_id, " +
            "c.paragraph_idx, c.anchor_text, c.confidence, " +
            "rm.title AS raw_title, " +
            "wc.ordinal AS chunk_ordinal, " +
            "wc.start_offset, wc.end_offset, " +
            "SUBSTRING(wc.content, 1, 200) AS snippet " +
            "FROM mate_wiki_page_citation c " +
            "JOIN mate_wiki_chunk wc ON c.chunk_id = wc.id " +
            "LEFT JOIN mate_wiki_raw_material rm ON wc.raw_id = rm.id " +
            "WHERE c.page_id = #{pageId} AND c.deleted = 0")
    List<PageCitationWithRaw> listWithRawByPageId(@Param("pageId") Long pageId);

    @Select("SELECT page_id FROM mate_wiki_page_citation " +
            "WHERE chunk_id = #{chunkId} AND deleted = 0")
    List<Long> listPageIdsByChunkId(@Param("chunkId") Long chunkId);

    @Select("SELECT DISTINCT c.page_id FROM mate_wiki_page_citation c " +
            "JOIN mate_wiki_chunk wc ON c.chunk_id = wc.id " +
            "WHERE wc.raw_id = #{rawId} AND c.deleted = 0 AND wc.deleted = 0")
    List<Long> listPageIdsByRawId(@Param("rawId") Long rawId);

    @Update("UPDATE mate_wiki_page_citation SET deleted = 1 WHERE page_id = #{pageId}")
    void softDeleteByPageId(@Param("pageId") Long pageId);

    @Select("<script>SELECT chunk_id, page_id FROM mate_wiki_page_citation " +
            "WHERE chunk_id IN " +
            "<foreach collection='chunkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND deleted = 0</script>")
    List<ChunkPageRef> listByChunkIds(@Param("chunkIds") Collection<Long> chunkIds);
}
