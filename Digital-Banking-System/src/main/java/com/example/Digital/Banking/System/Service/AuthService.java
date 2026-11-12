package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.LoginRequestDTO;
import com.example.Digital.Banking.System.DTO.LoginResponseDTO;
import com.example.Digital.Banking.System.DTO.RegisterRequestDTO;
import com.example.Digital.Banking.System.DTO.UserResponseDTO;
import com.example.Digital.Banking.System.Entity.User;
import com.example.Digital.Banking.System.Enum.Role;
import com.example.Digital.Banking.System.Exception.EmailAlreadyExistsException;
import com.example.Digital.Banking.System.Exception.InvalidCredentialsException;
import com.example.Digital.Banking.System.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.Digital.Banking.System.Enum.KycStatus.PENDING;
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public void Register(RegisterRequestDTO req) {

        if (userRepository.existsByEmail(req.getEmail())) {
            throw new EmailAlreadyExistsException("Email Already Exists");
        }
        User user = new User();
        user.setName(req.getName());
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setPhone(req.getPhone());
        user.setRole(Role.CUSTOMER);
        user.setKycStatus(PENDING);

        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public LoginResponseDTO login(LoginRequestDTO req) {

        // 1) نضّف الإيميل
        String email = req.getEmail().trim().toLowerCase();

        // 2) دوّر على اليوزر
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        // 3) قارن الباسورد (الخام الأول، والـ hash التاني)
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        // 4) ولّد الـ token
        String token = jwtService.generateAccessToken(user);

        // 5) ابني الـ response
        UserResponseDTO userDto = new UserResponseDTO(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getKycStatus());

        return new LoginResponseDTO(
                token,
                "Bearer",
                jwtService.getAccessExpiration(),
                userDto);
    }
}
