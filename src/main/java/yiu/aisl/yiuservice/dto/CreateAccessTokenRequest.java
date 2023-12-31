package yiu.aisl.yiuservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CreateAccessTokenRequest {
    private String accessToken;
    private String refreshToken;
}
