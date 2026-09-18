package com.ruoyi.ai.service;
import java.util.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import com.ruoyi.ai.domain.*; import com.ruoyi.ai.dto.AiPreferenceRequest; import com.ruoyi.ai.mapper.AiUserPreferenceMapper; import com.ruoyi.common.exception.ServiceException; import com.ruoyi.common.utils.SecurityUtils; import com.ruoyi.common.utils.StringUtils;
@Service
public class AiPreferenceService {
 private static final Set<String> FONTS=Set.of("small","standard","large","xlarge"),SHORTCUTS=Set.of("enter","ctrl-enter"),MODES=Set.of("floating","dock","last");
 private final AiUserPreferenceMapper mapper; private final AiConfigService config;
 public AiPreferenceService(AiUserPreferenceMapper mapper,AiConfigService config){this.mapper=mapper;this.config=config;}
 public AiUserPreference getCurrent(){Long uid=SecurityUtils.getUserId();AiUserPreference p=mapper.selectByUserId(uid);return p==null?defaults(uid):p;}
 public Map<String,Object> view(){AiUserPreference p=getCurrent();Map<String,Object> v=new LinkedHashMap<>();v.put("defaultModelId",p.getDefaultModelId());v.put("defaultReasoningEffort",p.getDefaultReasoningEffort());v.put("chatFontSize",p.getChatFontSize());v.put("sendShortcut",p.getSendShortcut());v.put("doubleEscEnabled","0".equals(p.getDoubleEscEnabled()));v.put("historyEntryVisible","0".equals(p.getHistoryEntryVisible()));v.put("autoRestoreLastConversation","0".equals(p.getAutoRestoreLastConversation()));v.put("assistantOpenMode",p.getAssistantOpenMode());return v;}
 @Transactional public Map<String,Object> save(AiPreferenceRequest r){Long uid=SecurityUtils.getUserId();AiUserPreference p=mapper.selectByUserId(uid);boolean fresh=p==null;if(fresh)p=defaults(uid);
  if(r.getDefaultModelId()!=null){AiModel m=config.requireEnabledSystemModel(r.getDefaultModelId());p.setDefaultModelId(m.getModelId());p.setDefaultReasoningEffort(config.resolveReasoningEffort(m,r.getDefaultReasoningEffort()));}
  else if(r.getDefaultReasoningEffort()!=null&&p.getDefaultModelId()!=null){AiModel m=config.requireEnabledSystemModel(p.getDefaultModelId());p.setDefaultReasoningEffort(config.resolveReasoningEffort(m,r.getDefaultReasoningEffort()));}
  if(r.getChatFontSize()!=null){if(!FONTS.contains(r.getChatFontSize()))throw new ServiceException("不支持的字体大小");p.setChatFontSize(r.getChatFontSize());}
  if(r.getSendShortcut()!=null){if(!SHORTCUTS.contains(r.getSendShortcut()))throw new ServiceException("不支持的发送快捷键");p.setSendShortcut(r.getSendShortcut());}
  if(r.getAssistantOpenMode()!=null){if(!MODES.contains(r.getAssistantOpenMode()))throw new ServiceException("不支持的 AI 打开方式");p.setAssistantOpenMode(r.getAssistantOpenMode());}
  if(r.getDoubleEscEnabled()!=null)p.setDoubleEscEnabled(r.getDoubleEscEnabled()?"0":"1");if(r.getHistoryEntryVisible()!=null)p.setHistoryEntryVisible(r.getHistoryEntryVisible()?"0":"1");if(r.getAutoRestoreLastConversation()!=null)p.setAutoRestoreLastConversation(r.getAutoRestoreLastConversation()?"0":"1");
  if(fresh)mapper.insert(p);else mapper.update(p);return view();}
 public AiModel preferredModel(){AiUserPreference p=getCurrent();if(p.getDefaultModelId()!=null){try{return config.requireEnabledSystemModel(p.getDefaultModelId());}catch(Exception ignored){}}return config.getDefaultEnabledModel();}
 public String preferredReasoning(AiModel model){AiUserPreference p=getCurrent();if(model!=null&&Objects.equals(model.getModelId(),p.getDefaultModelId())&&StringUtils.isNotBlank(p.getDefaultReasoningEffort()))return config.resolveReasoningEffort(model,p.getDefaultReasoningEffort());return config.resolveReasoningEffort(model,null);}
 private AiUserPreference defaults(Long uid){AiUserPreference p=new AiUserPreference();p.setUserId(uid);p.setChatFontSize("standard");p.setSendShortcut("enter");p.setDoubleEscEnabled("0");p.setHistoryEntryVisible("1");p.setAutoRestoreLastConversation("1");p.setAssistantOpenMode("last");return p;}
}