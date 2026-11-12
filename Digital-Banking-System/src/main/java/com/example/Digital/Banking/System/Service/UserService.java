package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.ChangeKycStatusRequestDTO;
import com.example.Digital.Banking.System.DTO.ChangeRoleRequestDTO;
import com.example.Digital.Banking.System.DTO.RegisterRequestDTO;
import com.example.Digital.Banking.System.DTO.UpdateUserRequestDTO;
import com.example.Digital.Banking.System.Entity.User;
import com.example.Digital.Banking.System.Enum.KycStatus;
import com.example.Digital.Banking.System.Enum.Role;
import com.example.Digital.Banking.System.Exception.EmailAlreadyExistsException;
import com.example.Digital.Banking.System.Exception.InvalidKycTransitionException;
import com.example.Digital.Banking.System.Exception.NotFoundException;
import com.example.Digital.Banking.System.Repository.UserRepository;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.Digital.Banking.System.Enum.KycStatus.*;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }


    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User with id " + id + " not found"));
    }

    @Transactional
    public void update(Long id, UpdateUserRequestDTO req) {
        if (!userRepository.existsById(id)) {
            throw new NotFoundException("User not found");
        }
        User user = userRepository.findById(id).get(); // get() --> inside class optional

        if (req.getName() != null) {
            user.setName(req.getName().trim());
        }
        if (req.getPhone() != null) {
            user.setPhone(req.getPhone());
        }

    }
    @Transactional
    public void changeKycStatus(Long id, ChangeKycStatusRequestDTO req) {
        User user = userRepository.findById(id).get();

        KycStatus current = user.getKycStatus();
        KycStatus target = req.getKycStatus();

        if (!isTransitionAllowed(current, target)) {
            throw new InvalidKycTransitionException("Invalid Kyc Status");
        }

        user.setKycStatus(target);
        // مفيش save(): الـ dirty checking هيعمل UPDATE عند الـ commit
    }

    @Transactional
    public void changeRole(Long id, ChangeRoleRequestDTO req) {
        User user = findById(id);

        Role target = req.getRole();

        if (user.getRole() == target) {
            throw new IllegalStateException("User already has role " + target);
        }

        user.setRole(target);
        // مفيش save(): الـ dirty checking هيعمل UPDATE عند الـ commit
    }

    private boolean isTransitionAllowed(KycStatus from, KycStatus to) {
        return switch (from) {
            case PENDING  -> to == VERIFIED || to == REJECTED;
            case REJECTED -> to == PENDING;
            case VERIFIED -> false;
        };
    }
}
