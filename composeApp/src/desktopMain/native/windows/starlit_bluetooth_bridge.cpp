// Bluetooth Classic RFCOMM SDP service for Starlit desktop remote.
// Each accepted peer is forwarded exclusively to a localhost listener owned
// by the signed-in application. Encryption, account verification, command
// authorization and two-person pairing live in the Kotlin remote protocol.
#include <winsock2.h>
#include <ws2bth.h>
#include <windows.h>
#include <atomic>
#include <cstdlib>
#include <iostream>
#include <thread>

#pragma comment(lib, "Ws2_32.lib")

static const GUID kStarlitRemoteService = {
    0x7e454db1, 0x4605, 0x4d90,
    {0xb8, 0xc6, 0xf8, 0x16, 0x7d, 0x83, 0x34, 0x4e}
};

static std::atomic<bool> s_active{false};

static bool sendAll(SOCKET dest, const char* data, int size) {
    while (size > 0) {
        int wrote = send(dest, data, size, 0);
        if (wrote <= 0) return false;
        data += wrote;
        size -= wrote;
    }
    return true;
}

static void bridgePeer(SOCKET peer, unsigned short localPort) {
    SOCKET local = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (local == INVALID_SOCKET) {
        closesocket(peer);
        s_active.store(false);
        return;
    }
    sockaddr_in loopback{};
    loopback.sin_family = AF_INET;
    loopback.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    loopback.sin_port = htons(localPort);
    if (connect(local, reinterpret_cast<sockaddr*>(&loopback), sizeof(loopback)) == 0) {
        unsigned long idleSeconds = 0;
        while (idleSeconds < 300) {
            fd_set readable;
            FD_ZERO(&readable);
            FD_SET(peer, &readable);
            FD_SET(local, &readable);
            timeval tv{1, 0};
            int ready = select(0, &readable, nullptr, nullptr, &tv);
            if (ready == SOCKET_ERROR) break;
            if (ready == 0) {
                ++idleSeconds;
                continue;
            }
            idleSeconds = 0;
            char buf[8192];
            if (FD_ISSET(peer, &readable)) {
                int count = recv(peer, buf, sizeof(buf), 0);
                if (count <= 0 || !sendAll(local, buf, count)) break;
            }
            if (FD_ISSET(local, &readable)) {
                int count = recv(local, buf, sizeof(buf), 0);
                if (count <= 0 || !sendAll(peer, buf, count)) break;
            }
        }
    }
    shutdown(peer, SD_BOTH);
    shutdown(local, SD_BOTH);
    closesocket(peer);
    closesocket(local);
    s_active.store(false);
}

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 2;
    wchar_t* end = nullptr;
    unsigned long port = wcstoul(argv[1], &end, 10);
    if (*end || port < 1024 || port > 65535) return 2;
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa)) return 3;
    SOCKET listener = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (listener == INVALID_SOCKET) {
        std::cout << "UNAVAILABLE" << std::endl;
        WSACleanup();
        return 4;
    }
    SOCKADDR_BTH address{};
    address.addressFamily = AF_BTH;
    address.btAddr = 0;
    address.serviceClassId = GUID{};
    address.port = BT_PORT_ANY;
    if (bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR ||
        listen(listener, 1) == SOCKET_ERROR) {
        std::cout << "UNAVAILABLE" << std::endl;
        closesocket(listener);
        WSACleanup();
        return 5;
    }
    int addressSize = sizeof(address);
    if (getsockname(listener, reinterpret_cast<sockaddr*>(&address), &addressSize) == SOCKET_ERROR) {
        std::cout << "UNAVAILABLE" << std::endl;
        closesocket(listener);
        WSACleanup();
        return 6;
    }

    CSADDR_INFO cs{};
    cs.LocalAddr.lpSockaddr = reinterpret_cast<sockaddr*>(&address);
    cs.LocalAddr.iSockaddrLength = sizeof(address);
    cs.iSocketType = SOCK_STREAM;
    cs.iProtocol = BTHPROTO_RFCOMM;
    WSAQUERYSETW service{};
    service.dwSize = sizeof(service);
    service.lpszServiceInstanceName = const_cast<wchar_t*>(L"Starlit Remote");
    service.lpServiceClassId = const_cast<GUID*>(&kStarlitRemoteService);
    service.dwNameSpace = NS_BTH;
    service.dwNumberOfCsAddrs = 1;
    service.lpcsaBuffer = &cs;
    if (WSASetServiceW(&service, RNRSERVICE_REGISTER, 0) == SOCKET_ERROR) {
        std::cout << "UNAVAILABLE" << std::endl;
        closesocket(listener);
        WSACleanup();
        return 7;
    }

    std::cout << "READY" << std::endl;
    // Parent holds stdin open and closes it on exit. The helper never
    // persists after the desktop application has stopped.
    std::thread([] {
        while (std::cin.get() != EOF) {}
        ExitProcess(0);
    }).detach();

    for (;;) {
        SOCKADDR_BTH remote{};
        int remoteLength = sizeof(remote);
        SOCKET peer = accept(listener, reinterpret_cast<sockaddr*>(&remote), &remoteLength);
        if (peer == INVALID_SOCKET) break;
        if (s_active.exchange(true)) {
            closesocket(peer);
            continue;
        }
        std::thread(bridgePeer, peer, static_cast<unsigned short>(port)).detach();
    }

    WSASetServiceW(&service, RNRSERVICE_DELETE, 0);
    closesocket(listener);
    WSACleanup();
    return 0;
}
