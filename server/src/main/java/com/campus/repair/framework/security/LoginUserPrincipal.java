package com.campus.repair.framework.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginUserPrincipal implements Serializable {
    private Long userId;
    private String username;
    private List<String> roles;
}
