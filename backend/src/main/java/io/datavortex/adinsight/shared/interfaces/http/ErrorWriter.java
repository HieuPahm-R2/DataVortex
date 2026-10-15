package io.datavortex.adinsight.shared.interfaces.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class ErrorWriter {
    private final ObjectMapper mapper;

    public ErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(
                response.getOutputStream(),
                new ApiError(code, message, (String) request.getAttribute("requestId")));
    }
}
