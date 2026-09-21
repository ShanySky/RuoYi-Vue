package com.ruoyi.ai.workspace;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.server.ApiContractSchema;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;

@Service
public class AiWorkspaceTools
{
    private final AiWorkspaceAccess access;
    private final AiWorkspaceClient client;
    public AiWorkspaceTools(AiWorkspaceAccess access, AiWorkspaceClient client) { this.access = access; this.client = client; }
    public static boolean supports(String name) { return java.util.Set.of("workspace_execute", "workspace_import_result", "workspace_publish").contains(name == null ? "" : name); }

    public List<ApprovedTool> definitions()
    {
        try { access.authorization(); if (!client.ready()) return List.of(); }
        catch (Exception denied) { return List.of(); }
        var execute = ApiContractSchema.object();
        ApiContractSchema.add(execute, "command", Map.of("type", "string", "maxLength", 12000), true);
        ApiContractSchema.add(execute, "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 30), false);
        var input = ApiContractSchema.object();
        ApiContractSchema.add(input, "resultId", Map.of("type", "string", "maxLength", 80), true);
        ApiContractSchema.add(input, "path", Map.of("type", "string", "maxLength", 240), true);
        var publish = ApiContractSchema.object();
        ApiContractSchema.add(publish, "path", Map.of("type", "string", "maxLength", 240), true);
        ApiContractSchema.add(publish, "name", Map.of("type", "string", "maxLength", 100), true);
        return List.of(
            new ApprovedTool("workspace_execute", "在本人本任务的隔离 /work 目录运行命令。无网络和宿主文件访问。Python 已有 python-docx、openpyxl、reportlab。"
                    + "单命令最多30秒、输出8KiB，单文件8MiB，工作区64MiB和512文件节点；任务结束即清理，需用 workspace_publish 发布成果。不得把这里的本地文件写入视为业务接口写入。", execute, "WORKSPACE", "ai:workspace:use"),
            new ApprovedTool("workspace_import_result", "把本人同运行、仍有业务权限且未到期的结果句柄保存为 /work 内的 JSON 文件。只接受不存在的相对路径，可直接使用 input.json。", input, "WORKSPACE", "ai:workspace:use"),
            new ApprovedTool("workspace_publish", "将 /work 内的正规文件发布到本会话成果列表，用户可鉴权下载。相对路径，拒绝链接；单任务最多8件/16MiB，单件8MiB，保留24小时。", publish, "WORKSPACE", "ai:workspace:use"));
    }
}
