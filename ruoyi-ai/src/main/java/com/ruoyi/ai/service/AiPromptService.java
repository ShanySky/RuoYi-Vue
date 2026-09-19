package com.ruoyi.ai.service;
import java.util.*; import java.util.regex.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import com.ruoyi.ai.domain.*; import com.ruoyi.ai.mapper.*; import com.ruoyi.common.exception.ServiceException; import com.ruoyi.common.utils.SecurityUtils; import com.ruoyi.common.utils.StringUtils;
@Service
public class AiPromptService {
 public static final String SYSTEM="SYSTEM", COMPACTION="COMPACTION";
 private static final Pattern VARIABLE=Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)\\}\\}");
 private static final Map<String,Set<String>> ALLOWED=Map.of(SYSTEM,Set.of("currentUser","route"),COMPACTION,Set.of("conversationId","modelCode"));
 private final AiPromptMapper mapper; private final AiPromptVersionMapper versions;
 public AiPromptService(AiPromptMapper mapper,AiPromptVersionMapper versions){this.mapper=mapper;this.versions=versions;}
 public List<AiPrompt> list(){return mapper.selectAll();}
 public AiPrompt require(String type){
  AiPrompt p=mapper.selectByType(type);if(p==null)throw new ServiceException("AI Prompt 未初始化："+type);
  AiPromptVersion version;
  if("0".equals(p.getEnabled())){
   version=versions.selectByTypeAndVersion(type,p.getVersionNo());
  }else{
   p.setContent(p.getDefaultContent());
   version=versions.selectLatestByContent(type,p.getContent());
  }
  if(version==null)throw new ServiceException("AI Prompt 历史版本缺失："+type+"#"+p.getVersionNo());
  p.setVersionNo(version.getVersionNo());
  return p;
 }
 @Transactional public AiPrompt update(String type,String content,Boolean enabled){
  validateType(type);if(content==null&&enabled==null)throw new ServiceException("Prompt 内容或启用状态至少需要修改一项");
  String normalized=null;if(content!=null){if(StringUtils.isBlank(content))throw new ServiceException("Prompt 不能为空");if(content.length()>50000)throw new ServiceException("Prompt 过长");normalized=content.trim();validateTemplate(type,normalized);}
  AiPrompt current=lock(type);String username=SecurityUtils.getUsername();String desiredEnabled=enabled==null?current.getEnabled():(enabled?"0":"1");
  boolean contentChanged=normalized!=null&&!Objects.equals(normalized,current.getContent());
  if(contentChanged){
   int next=current.getVersionNo()+1;
   if(mapper.updateContentVersion(type,normalized,next,desiredEnabled,username)!=1)throw new ServiceException("Prompt 更新失败");
   insertVersion(type,next,normalized,username);
  }else if(enabled!=null&&!Objects.equals(desiredEnabled,current.getEnabled())){
   if(mapper.updateEnabled(type,desiredEnabled,username)!=1)throw new ServiceException("Prompt 状态更新失败");
  }
  return mapper.selectByType(type);
 }
 @Transactional public AiPrompt restore(String type){
  validateType(type);AiPrompt current=lock(type);String username=SecurityUtils.getUsername();
  if(!Objects.equals(current.getContent(),current.getDefaultContent())){
   int next=current.getVersionNo()+1;
   if(mapper.updateContentVersion(type,current.getDefaultContent(),next,"0",username)!=1)throw new ServiceException("Prompt 恢复默认失败");
   insertVersion(type,next,current.getDefaultContent(),username);
  }else if(!"0".equals(current.getEnabled())){
   if(mapper.updateEnabled(type,"0",username)!=1)throw new ServiceException("Prompt 恢复默认失败");
  }
  return mapper.selectByType(type);
 }
 public AiPromptVersion version(String type,Integer versionNo){validateType(type);if(versionNo==null)return null;return versions.selectByTypeAndVersion(type,versionNo);}
 public String render(AiPrompt prompt,Map<String,?> variables){if(prompt==null)return "";String content=StringUtils.defaultString(prompt.getContent());validateTemplate(prompt.getPromptType(),content);Matcher m=VARIABLE.matcher(content);StringBuffer out=new StringBuffer();while(m.find()){Object value=variables==null?null:variables.get(m.group(1));m.appendReplacement(out,Matcher.quoteReplacement(value==null?"":String.valueOf(value)));}m.appendTail(out);return out.toString();}
 private AiPrompt lock(String type){AiPrompt p=mapper.selectByTypeForUpdate(type);if(p==null)throw new ServiceException("AI Prompt 未初始化："+type);return p;}
 private void insertVersion(String type,Integer versionNo,String content,String username){AiPromptVersion v=new AiPromptVersion();v.setPromptType(type);v.setVersionNo(versionNo);v.setContent(content);v.setCreateBy(username);versions.insert(v);}
 private void validateTemplate(String type,String content){Matcher m=VARIABLE.matcher(content);Set<String> allowed=ALLOWED.getOrDefault(type,Set.of());String stripped=m.replaceAll("");m.reset();while(m.find())if(!allowed.contains(m.group(1)))throw new ServiceException("Prompt 包含不支持的模板变量："+m.group(1));if(stripped.contains("{{")||stripped.contains("}}"))throw new ServiceException("Prompt 模板变量格式不完整");}
 private void validateType(String type){if(!SYSTEM.equals(type)&&!COMPACTION.equals(type))throw new ServiceException("不支持的 Prompt 类型");}
}
