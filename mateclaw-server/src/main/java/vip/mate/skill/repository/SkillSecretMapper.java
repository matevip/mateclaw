package vip.mate.skill.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mate.skill.secret.SkillSecretEntity;

@Mapper
public interface SkillSecretMapper extends BaseMapper<SkillSecretEntity> {

    /** Hard-delete every secret tied to a skill — invoked when a skill is
     *  hard-deleted, so secrets don't outlive the skill they belong to. */
    @Delete("DELETE FROM mate_skill_secret WHERE skill_id = #{skillId}")
    int hardDeleteBySkillId(@Param("skillId") Long skillId);
}
