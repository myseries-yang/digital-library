package com.example.library.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    public OpenAPI libraryOpenApi() {
        return new OpenAPI().info(new Info().title("电子图书馆 / Digital Library").version("1.0.0")
                .description("按图书馆账号独立借阅。借还与当前借阅使用 X-Library-Account-Id。"
                        + "该请求头是演示身份约定，不是真实认证。时间使用 UTC ISO 8601，page 从零开始。"
                        + "错误为 application/problem+json，包含稳定 code 与 requestId。"
                        + "Redis 仅用于锁；数据关联由应用事务校验，不建立数据库外键。"))
                .addServersItem(new Server().url("/").description("当前服务地址"))
                .components(new Components().addSchemas("LibraryProblem", new Schema<Object>()
                        .type("object").addProperty("type", new StringSchema())
                        .addProperty("title", new StringSchema()).addProperty("status", new IntegerSchema())
                        .addProperty("detail", new StringSchema()).addProperty("instance", new StringSchema())
                        .addProperty("code", new StringSchema().description("稳定业务错误码"))
                        .addProperty("requestId", new StringSchema().description("与日志及 X-Request-Id 对应"))));
    }

    @Bean
    public OperationCustomizer libraryErrorResponses() {
        return (operation, method) -> {
            for (String status : new String[]{"400", "404", "500", "503"}) {
                operation.getResponses().addApiResponse(status, problemResponse(errorDescription(status)));
            }
            if (method.getMethod().getName().equals("borrow") || method.getMethod().getName().equals("update")) {
                operation.getResponses().addApiResponse("409", problemResponse("重复在借、名额/额度不足或规则版本冲突"));
            }
            if (method.getMethod().getName().equals("returnLoan")) {
                operation.getResponses().addApiResponse("403", problemResponse("该借阅不属于当前账号"));
            }
            return operation;
        };
    }

    private String errorDescription(String status) {
        switch (status) {
            case "400":
                return "参数、账号请求头或请求格式不正确";
            case "404":
                return "请求的资源不存在";
            case "503":
                return "服务暂不可用或提交结果未确认；请结合 code 判定，不自动重放借阅";
            default:
                return "内部错误或数据一致性异常";
        }
    }

    private ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(
                "application/problem+json", new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new Schema<Object>().$ref("#/components/schemas/LibraryProblem"))));
    }
}
