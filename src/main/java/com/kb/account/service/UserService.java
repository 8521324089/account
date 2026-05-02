package com.kb.account.service;

import com.kb.account.dto.UserDTO;
import com.kb.account.exception.ResourceNotFoundException;
import com.kb.account.model.User;
import com.kb.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserDTO getUserById(Long id) {
        return userRepository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional(readOnly = true)
    public UserDTO getUserByGoogleId(String googleId) {
        return userRepository.findByGoogleId(googleId)
                .map(this::convertToDTO)
                .orElseThrow(() -> new ResourceNotFoundException("User", googleId));
    }

    @Transactional
    public UserDTO updateUser(Long id, UserDTO userDTO) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        if (userDTO.getPhone() != null) user.setPhone(userDTO.getPhone());
        if (userDTO.getUpiId() != null) user.setUpiId(userDTO.getUpiId());
        User updated = userRepository.save(user);
        log.info("User updated: id={}", updated.getId());
        return convertToDTO(updated);
    }

    public User getUserEntityByGoogleId(String googleId) {
        return userRepository.findByGoogleId(googleId)
                .orElseThrow(() -> new ResourceNotFoundException("User", googleId));
    }

    private UserDTO convertToDTO(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .upiId(user.getUpiId())
                .pictureUrl(user.getPictureUrl())
                .build();
    }
}
