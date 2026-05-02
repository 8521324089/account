package com.kb.account.service;

import com.kb.account.model.User;
import com.kb.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String googleId = oAuth2User.getAttribute("sub");
        String name = oAuth2User.getAttribute("name");
        String email = oAuth2User.getAttribute("email");
        String picture = oAuth2User.getAttribute("picture");
        String accessToken = userRequest.getAccessToken().getTokenValue();

        User user = userRepository.findByGoogleId(googleId).orElseGet(() -> {
            User newUser = new User();
            newUser.setGoogleId(googleId);
            log.info("New user registered via Google: {}", email);
            return newUser;
        });

        user.setName(name);
        user.setEmail(email);
        user.setPictureUrl(picture);
        user.setAccessToken(accessToken);

        // Store refresh token if available
        if (userRequest.getAdditionalParameters().containsKey("refresh_token")) {
            user.setRefreshToken((String) userRequest.getAdditionalParameters().get("refresh_token"));
        }

        userRepository.save(user);
        log.info("User authenticated: id={}, email={}", user.getId(), email);

        return oAuth2User;
    }
}
