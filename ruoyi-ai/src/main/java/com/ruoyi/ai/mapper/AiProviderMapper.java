package com.ruoyi.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.ruoyi.ai.domain.AiProvider;

public interface AiProviderMapper
{
    @Select("select provider_id as providerId, name, provider_type as providerType, base_url as baseUrl, "
            + "token_cipher as tokenCipher, enabled, timeout_seconds as timeoutSeconds, create_by as createBy, "
            + "create_time as createTime, update_by as updateBy, update_time as updateTime, remark "
            + "from ai_provider order by provider_id limit 1")
    AiProvider selectFirst();

    @Insert("insert into ai_provider(name, provider_type, base_url, token_cipher, enabled, timeout_seconds, create_by, create_time, remark) "
            + "values(#{name}, #{providerType}, #{baseUrl}, #{tokenCipher}, #{enabled}, #{timeoutSeconds}, #{createBy}, sysdate(), #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "providerId")
    int insert(AiProvider provider);

    @Update("update ai_provider set name=#{name}, provider_type=#{providerType}, base_url=#{baseUrl}, token_cipher=#{tokenCipher}, "
            + "enabled=#{enabled}, timeout_seconds=#{timeoutSeconds}, update_by=#{updateBy}, update_time=sysdate(), remark=#{remark} "
            + "where provider_id=#{providerId}")
    int update(AiProvider provider);
}
