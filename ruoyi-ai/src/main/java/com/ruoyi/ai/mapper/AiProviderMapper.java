package com.ruoyi.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.ruoyi.ai.domain.AiProvider;

public interface AiProviderMapper
{
    @Select("select provider_id, name, provider_type, base_url, token_cipher, enabled, timeout_seconds, create_by, create_time, update_by, update_time, remark from ai_provider order by provider_id limit 1")
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
