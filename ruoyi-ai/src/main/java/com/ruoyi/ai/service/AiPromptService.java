package com.ruoyi.ai.service;
import java.util.*; import java.util.regex.*; import org.springframework.stereotype.Service; import com.ruoyi.ai.domain.AiPrompt; import com.ruoyi.ai.mapper.AiPromptMapper; import com.ruoyi.common.exception.ServiceException; import com.ruoyi.common.utils.SecurityUtils; import com.ruoyi.common.utils.StringUtils;
@Service
public class AiPromptService {
 public static final String SYSTEM="SYSTEM", COMPACTION="COMPACTION";
 private static final Pattern VARIABLE=Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)\\}\\}");
 private static final Map<String,Set<String>> ALLOWED=Map.of(SYSTEM,Set.of("currentUser","route"),COMPACTION,Set.of("conversationId","modelCode"));
 private final AiPromptMapper mapper; public AiPromptService(AiPromptMapper mapper){this.mapper=mapper;}
 public List<AiPrompt> list(){return mapper.selectAll();}
 public AiPrompt require(String type){AiPrompt p=mapper.selectByType(type);if(p==null)throw new ServiceException("AI Prompt 未初始化："+type);if(!"0".equals(p.getEnabled()))p.setContent(p.getDefaultContent());return p;}
 public AiPrompt update(String type,String content,Boolean enabled){validateType(type);if(content==null&&enabled==null)throw new ServiceException("Prompt 内容或启用状态至少需要修改一项");String normalized=null;if(content!=null){if(StringUtils.isBlank(content))throw new ServiceException("Prompt 不能为空");if(content.length()>50000)throw new ServiceException("Prompt 过长");normalized=content.trim();validateTemplate(type,normalized);}mapper.update(type,normalized,enabled==null?null:(enabled?"0":"1"),SecurityUtils.getUsername());return mapper.selectByType(type);}
 public AiPrompt restore(String type){validateType(type);mapper.restoreDefault(type,SecurityUtils.getUsername());return mapper.selectByType(type);}
 public String render(AiPrompt prompt,Map<String,?> variables){if(prompt==null)return "";String content=StringUtils.defaultString(prompt.getContent());validateTemplate(prompt.getPromptType(),content);Matcher m=VARIABLE.matcher(content);StringBuffer out=new StringBuffer();while(m.find()){Object value=variables==null?null:variables.get(m.group(1));m.appendReplacement(out,Matcher.quoteReplacement(value==null?"":String.valueOf(value)));}m.appendTail(out);return out.toString();}
 private void validateTemplate(String type,String content){Matcher m=VARIABLE.matcher(content);Set<String> allowed=ALLOWED.getOrDefault(type,Set.of());String stripped=m.replaceAll("");m.reset();while(m.find())if(!allowed.contains(m.group(1)))throw new ServiceException("Prompt 包含不支持的模板变量："+m.group(1));if(stripped.contains("{{")||stripped.contains("}}"))throw new ServiceException("Prompt 模板变量格式不完整");}
 private void validateType(String type){if(!SYSTEM.equals(type)&&!COMPACTION.equals(type))throw new ServiceException("不支持的 Prompt 类型");}
}
