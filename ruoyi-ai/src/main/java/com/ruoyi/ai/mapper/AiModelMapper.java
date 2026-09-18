package com.ruoyi.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.ruoyi.ai.domain.AiModel;

public interface AiModelMapper
{
    String SELECT_FIELDS = "select model_id as modelId, provider_id as providerId, model_code as modelCode, "
            + "display_name as displayName, enabled, default_model as defaultModel, tool_capability as toolCapability, "
            + "reasoning_capability as reasoningCapability, default_reasoning_effort as defaultReasoningEffort, "
            + "last_sync_time as lastSyncTime, create_by as createBy, create_time as createTime, update_by as updateBy, "
            + "update_time as updateTime, remark ";

    @Select(SELECT_FIELDS + "from ai_model where provider_id=#{providerId} order by model_code")
    List<AiModel> selectByProviderId(Long providerId);

    @Select(SELECT_FIELDS + "from ai_model where enabled='0' order by default_model asc, model_code")
    List<AiModel> selectEnabled();

    @Select(SELECT_FIELDS + "from ai_model where model_id=#{modelId}")
    AiModel selectById(Long modelId);

    @Select(SELECT_FIELDS + "from ai_model where provider_id=#{providerId} and model_code=#{modelCode} limit 1")
    AiModel selectByProviderAndCode(@Param("providerId") Long providerId, @Param("modelCode") String modelCode);

    @Insert("insert into ai_model(provider_id, model_code, display_name, enabled, default_model, tool_capability, reasoning_capability, default_reasoning_effort, last_sync_time, create_by, create_time, remark) "
            + "values(#{providerId}, #{modelCode}, #{displayName}, #{enabled}, #{defaultModel}, #{toolCapability}, #{reasoningCapability}, #{defaultReasoningEffort}, #{lastSyncTime}, #{createBy}, sysdate(), #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "modelId")
    int insert(AiModel model);

    @Update("update ai_model set display_name=#{displayName}, last_sync_time=#{lastSyncTime}, update_by=#{updateBy}, update_time=sysdate() where model_id=#{modelId}")
    int updateSync(AiModel model);

    @Update("update ai_model set enabled=#{enabled}, update_by=#{updateBy}, update_time=sysdate() where model_id=#{modelId}")
    int updateEnabled(AiModel model);

    @Update("update ai_model set default_model='1', update_by=#{updateBy}, update_time=sysdate() where provider_id=#{providerId}")
    int clearDefault(@Param("providerId") Long providerId, @Param("updateBy") String updateBy);

    @Update("update ai_model set default_model='0', enabled='0', update_by=#{updateBy}, update_time=sysdate() where model_id=#{modelId}")
    int setDefault(@Param("modelId") Long modelId, @Param("updateBy") String updateBy);

    @Update("update ai_model set tool_capability=#{toolCapability}, update_by=#{updateBy}, update_time=sysdate() where model_id=#{modelId}")
    int updateToolCapability(AiModel model);

    @Update("update ai_model set reasoning_capability=#{reasoningCapability}, update_by=#{updateBy}, update_time=sysdate() where model_id=#{modelId}")
    int updateReasoningCapability(AiModel model);

    @Update("update ai_model set default_reasoning_effort=#{defaultReasoningEffort}, update_by=#{updateBy}, update_time=sysdate() where model_id=#{modelId}")
    int updateDefaultReasoningEffort(AiModel model);

    @Select(SELECT_FIELDS + "from ai_model where enabled='0' and default_model='0' order by model_id limit 1")
    AiModel selectDefaultEnabled();
}
