package vip.mate.stt;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * STT 语音识别 REST 端点
 */
@RestController
@RequestMapping("/api/v1/stt")
@RequiredArgsConstructor
public class SttController {

    private final SttService sttService;

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String language) throws Exception {

        Map<String, Object> result = sttService.transcribe(
                file.getBytes(),
                file.getOriginalFilename(),
                file.getContentType(),
                language
        );
        return ResponseEntity.ok(result);
    }
}
