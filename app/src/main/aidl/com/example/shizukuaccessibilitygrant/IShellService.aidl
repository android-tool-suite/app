package com.example.shizukuaccessibilitygrant;

interface IShellService {
    String run(in String[] command) = 1;
    void destroy() = 16777114;
}
