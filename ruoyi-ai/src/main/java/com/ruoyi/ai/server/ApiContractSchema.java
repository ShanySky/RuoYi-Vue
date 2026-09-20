package com.ruoyi.ai.server;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;

/** 从业务方法和对象属性派生参数；执行时仍由原入口完成业务校验。 */
@Component
public class ApiContractSchema
{
    public Map<String, Object> describe(HandlerMethod handler)
    {
        Map<String, Object> path = object();
        Map<String, Object> query = object();
        Map<String, Object> root = object();
        for (Parameter parameter : handler.getMethod().getParameters())
        {
            if (parameter.isAnnotationPresent(PathVariable.class))
            {
                PathVariable annotation = parameter.getAnnotation(PathVariable.class);
                add(path, name(annotation.name(), annotation.value(), parameter),
                        scalar(parameter.getType()), annotation.required());
            }
            else if (parameter.isAnnotationPresent(RequestBody.class))
            {
                if (properties(root).containsKey("body"))
                {
                    throw new IllegalArgumentException("存在多个请求体");
                }
                boolean validated = parameter.isAnnotationPresent(Validated.class)
                        || parameter.isAnnotationPresent(Valid.class);
                add(root, "body", type(parameter.getType(), validated, 0),
                        parameter.getAnnotation(RequestBody.class).required());
            }
            else if (parameter.isAnnotationPresent(RequestParam.class))
            {
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                boolean required = annotation.required()
                        && ValueConstants.DEFAULT_NONE.equals(annotation.defaultValue());
                add(query, name(annotation.name(), annotation.value(), parameter),
                        scalar(parameter.getType()), required);
            }
            else if (!parameter.getType().getPackageName().startsWith("com.ruoyi."))
            {
                throw new IllegalArgumentException("非标准参数必须显式声明绑定方式");
            }
            else
            {
                Map<String, Object> bean = type(parameter.getType(), false, 0);
                for (Map.Entry<String, Object> entry : properties(bean).entrySet())
                {
                    Map<String, Object> property = cast(entry.getValue());
                    if (!"object".equals(property.get("type")) && !"array".equals(property.get("type")))
                    {
                        add(query, entry.getKey(), property, false);
                    }
                }
            }
        }
        if (TableDataInfo.class.isAssignableFrom(handler.getMethod().getReturnType()))
        {
            add(query, "pageNum", Map.of("type", "integer", "minimum", 1, "maximum", 10000), false);
            add(query, "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 50), false);
        }
        if (!properties(path).isEmpty())
        {
            add(root, "path", path, !required(path).isEmpty());
        }
        if (!properties(query).isEmpty())
        {
            add(root, "query", query, !required(query).isEmpty());
        }
        return root;
    }

    private Map<String, Object> type(Class<?> clazz, boolean validated, int depth)
    {
        try
        {
            return scalar(clazz);
        }
        catch (IllegalArgumentException ignored)
        {
            // 复杂业务对象继续从真实可写属性提取，未知类型不猜测。
        }
        if (depth >= 3 || !clazz.getPackageName().startsWith("com.ruoyi."))
        {
            throw new IllegalArgumentException("不支持的参数对象类型");
        }
        Map<String, Object> schema = object();
        try
        {
            for (PropertyDescriptor property : Introspector.getBeanInfo(clazz, Object.class).getPropertyDescriptors())
            {
                if (property.getWriteMethod() == null || property.getReadMethod() == null
                        || property.getReadMethod().isAnnotationPresent(JsonIgnore.class))
                {
                    continue;
                }
                Field field = field(clazz, property.getName());
                if (field != null && field.isAnnotationPresent(JsonIgnore.class))
                {
                    continue;
                }
                Class<?> propertyType = property.getPropertyType();
                boolean mandatory = validated && (mandatory(property.getReadMethod()) || mandatory(field));
                // 任意映射和集合没有可靠元素契约，不向模型开放其赋值能力。
                if (Map.class.isAssignableFrom(propertyType) || Collection.class.isAssignableFrom(propertyType))
                {
                    if (mandatory) throw new IllegalArgumentException("必填集合没有可靠元素契约：" + property.getName());
                    continue;
                }
                Map<String, Object> value;
                try
                {
                    value = new LinkedHashMap<>(type(propertyType, validated, depth + 1));
                }
                catch (IllegalArgumentException unsupported)
                {
                    if (mandatory) throw new IllegalArgumentException("必填属性无法可靠披露：" + property.getName(), unsupported);
                    continue;
                }
                if (field != null && field.isAnnotationPresent(Excel.class))
                {
                    Excel excel = field.getAnnotation(Excel.class);
                    value.put("description", excel.name() + (excel.readConverterExp().isBlank()
                            ? "" : "（" + excel.readConverterExp() + "）"));
                }
                constraints(value, property.getReadMethod());
                if (field != null)
                {
                    constraints(value, field);
                }
                add(schema, property.getName(), value, mandatory);
            }
        }
        catch (java.beans.IntrospectionException error)
        {
            throw new IllegalArgumentException("无法解析业务对象", error);
        }
        return schema;
    }

    private Map<String, Object> scalar(Class<?> clazz)
    {
        if (clazz == String.class || clazz == char.class || clazz == Character.class
                || Date.class.isAssignableFrom(clazz) || clazz.getPackageName().startsWith("java.time"))
        {
            return Map.of("type", "string");
        }
        if (clazz == boolean.class || clazz == Boolean.class)
        {
            return Map.of("type", "boolean");
        }
        if (clazz.isEnum())
        {
            return Map.of("type", "string", "enum", java.util.Arrays.stream(clazz.getEnumConstants())
                    .map(Object::toString).toList());
        }
        if (clazz.isArray())
        {
            return Map.of("type", "array", "items", scalar(clazz.getComponentType()), "maxItems", 50);
        }
        if (clazz == byte.class || clazz == short.class || clazz == int.class || clazz == long.class
                || clazz == Byte.class || clazz == Short.class || clazz == Integer.class || clazz == Long.class)
        {
            return Map.of("type", "integer");
        }
        if (clazz == double.class || clazz == float.class || Number.class.isAssignableFrom(clazz))
        {
            return Map.of("type", "number");
        }
        throw new IllegalArgumentException("不是可描述的简单类型");
    }

    private void constraints(Map<String, Object> schema, AnnotatedElement source)
    {
        Size size = source.getAnnotation(Size.class);
        if (size != null && "string".equals(schema.get("type")))
        {
            schema.put("minLength", size.min());
            schema.put("maxLength", size.max());
        }
        Min min = source.getAnnotation(Min.class);
        Max max = source.getAnnotation(Max.class);
        Pattern pattern = source.getAnnotation(Pattern.class);
        if (min != null) schema.put("minimum", min.value());
        if (max != null) schema.put("maximum", max.value());
        if (pattern != null) schema.put("pattern", pattern.regexp());
    }

    private boolean mandatory(AnnotatedElement element)
    {
        return element != null && (element.isAnnotationPresent(NotNull.class)
                || element.isAnnotationPresent(NotBlank.class) || element.isAnnotationPresent(NotEmpty.class));
    }

    private Field field(Class<?> type, String name)
    {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
        {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private String name(String name, String value, Parameter parameter)
    {
        if (!name.isBlank()) return name;
        if (!value.isBlank()) return value;
        if (!parameter.isNamePresent()) throw new IllegalArgumentException("参数名未保留");
        return parameter.getName();
    }

    public void validate(Map<String, Object> schema, JsonNode value)
    {
        validate(schema, value, "参数", 0);
    }

    private void validate(Map<String, Object> schema, JsonNode value, String location, int depth)
    {
        if (value == null || value.isNull() || depth > 6) fail(location);
        String type = String.valueOf(schema.get("type"));
        switch (type)
        {
            case "object" -> {
                if (!value.isObject()) fail(location);
                Map<String, Object> properties = properties(schema);
                for (String required : required(schema)) if (!value.hasNonNull(required)) fail(location + "." + required);
                value.fields().forEachRemaining(entry -> {
                    if (!properties.containsKey(entry.getKey())) fail(location + "." + entry.getKey());
                    validate(cast(properties.get(entry.getKey())), entry.getValue(), location + "." + entry.getKey(), depth + 1);
                });
            }
            case "array" -> {
                if (!value.isArray() || value.size() > 50) fail(location);
                for (JsonNode item : value) validate(cast(schema.get("items")), item, location, depth + 1);
            }
            case "string" -> {
                if (!value.isTextual() || value.textValue().length() > 16000) fail(location);
                int length = value.textValue().length();
                if (schema.get("maxLength") instanceof Number max && length > max.intValue()) fail(location);
                if (schema.get("minLength") instanceof Number min && length < min.intValue()) fail(location);
                if (schema.get("enum") instanceof List<?> values && !values.contains(value.textValue())) fail(location);
                // 正则与业务校验继续交给原入口，避免额外执行模型可影响的正则。
            }
            case "integer", "number" -> {
                if (!value.isNumber() || (type.equals("integer") && !value.isIntegralNumber())) fail(location);
                BigDecimal number = value.decimalValue();
                if (schema.get("minimum") instanceof Number min && number.compareTo(new BigDecimal(min.toString())) < 0) fail(location);
                if (schema.get("maximum") instanceof Number max && number.compareTo(new BigDecimal(max.toString())) > 0) fail(location);
            }
            case "boolean" -> { if (!value.isBoolean()) fail(location); }
            default -> fail(location);
        }
    }

    private void fail(String location)
    {
        throw new ServiceException("接口参数不符合当前契约：" + location);
    }

    public static Map<String, Object> object()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", new LinkedHashMap<String, Object>());
        result.put("required", new ArrayList<String>());
        result.put("additionalProperties", false);
        return result;
    }

    public static void add(Map<String, Object> schema, String name, Map<String, Object> value, boolean mandatory)
    {
        properties(schema).put(name, value);
        if (mandatory && !required(schema).contains(name)) required(schema).add(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> properties(Map<String, Object> value) { return cast(value.get("properties")); }
    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> value) { return (List<String>) value.getOrDefault("required", List.of()); }
}
