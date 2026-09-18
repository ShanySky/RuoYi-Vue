package com.ruoyi.ai.service;
import java.util.List; import org.springframework.stereotype.Service; import com.ruoyi.ai.domain.AiPrompt; import com.ruoyi.ai.mapper.AiPromptMapper; import com.ruoyi.common.exception.ServiceException; import com.ruoyi.common.utils.SecurityUtils; import com.ruoyi.common.utils.StringUtils;
@Service
public class AiPromptService {
 public static final String SYSTEM="SYSTEM", COMPACTION="COMPACTION"; private final AiPromptMapper mapper;
 public AiPromptService(AiPromptMapper mapper){this.mapper=mapper;}
 public List<AiPrompt> list(){return mapper.selectAll();}
 public AiPrompt require(String type){AiPrompt p=mapper.selectByType(type);if(p==null||!"0".equals(p.getEnabled()))throw new ServiceException("AI Prompt 未初始化："+type);return p;}
 public AiPrompt update(String type,String content){validateType(type);if(StringUtils.isBlank(content))throw new ServiceException("Prompt 不能为空");if(content.length()>50000)throw new ServiceException("Prompt 过长");mapper.updateContent(type,content.trim(),SecurityUtils.getUsername());return require(type);}
 public AiPrompt restore(String type){validateType(type);mapper.restoreDefault(type,SecurityUtils.getUsername());return require(type);}
 private void validateType(String type){if(!SYSTEM.equals(type)&&!COMPACTION.equals(type))throw new ServiceException("不支持的 Prompt 类型");}
}