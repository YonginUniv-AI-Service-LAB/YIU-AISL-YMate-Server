package yiu.aisl.yiuservice.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public class DeliveryRequest {

    @Getter
    @Setter
    public static class DetailDTO {
        private Long dId;

    }

    @Getter
    @Setter
    public static class CreateDTO {

        private String title;

        private String contents;

        @DateTimeFormat(pattern = "yyyy-MM-dd kk:mm:ss")
        private LocalDateTime due;

        private Long food;

        private Long location;

        private String link;

        private Byte state = 1;
    }

    @Getter
    @Setter
    public static class UpdateDTO {

        private Long dId;

        private Long studentId;

        private String title;

        private String contents;

        @DateTimeFormat(pattern = "yyyy-MM-dd kk:mm:ss")
        private LocalDateTime due;

        private Long food;

        private Long location;

        private String link;

        private Byte state;
    }

    @Getter
    @Setter
    public static class DeleteDTO {
        private Long dId;

    }

    @Getter
    @Setter
    public static class ApplyDTO {

        private Long dId;

        private Long studentId;

        private String contents;

        private String details;

        private Byte state = 1;
    }

    @Getter
    @Setter
    public static class CancelDTO {
        private Long dcId;
    }

    @Getter
    @Setter
    public static class AcceptDTO {
        private Long dcId;
    }

    @Getter
    @Setter
    public static class rejectDTO {
        private Long dcId;
    }


}