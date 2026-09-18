package com.ruoyi.ai.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public class AiModelSelectionRequest
{
    @NotEmpty
    @Size(max = 50)
    private List<String> modelCodes;

    public List<String> getModelCodes()
    {
        return modelCodes;
    }

    public void setModelCodes(List<String> modelCodes)
    {
        this.modelCodes = modelCodes;
    }
}
