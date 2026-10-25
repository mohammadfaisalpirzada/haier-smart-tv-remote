import asyncio
import os
import sys
import socket
import logging
from typing import Optional
from fastapi import FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
import uvicorn
from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth

# Set up logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("HaierRemoteServer")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CERT_PATH = os.path.join(BASE_DIR, "cert.pem")
KEY_PATH = os.path.join(BASE_DIR, "key.pem")
DEFAULT_TV_IP = "192.168.0.107"

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

LOCAL_LAN_IP = get_local_ip()

app = FastAPI(title="Haier Smart TV PC & Mobile Remote")

class TVManager:
    def __init__(self, host: str = DEFAULT_TV_IP):
        self.host = host
        self.cert_path = CERT_PATH
        self.key_path = KEY_PATH
        self.remote: Optional[AndroidTVRemote] = None
        self.connected = False
        self.needs_pairing = False
        self.pairing_active = False
        self.last_error: Optional[str] = None
        
        # TV State info
        self.is_on: Optional[bool] = None
        self.volume_level: Optional[int] = None
        self.is_muted: Optional[bool] = None
        self.current_app: Optional[str] = None
        self._lock = asyncio.Lock()

    def _setup_remote(self):
        if self.remote is not None:
            try:
                self.remote.disconnect()
            except Exception:
                pass
        self.remote = AndroidTVRemote(
            client_name="HaierRemoteController",
            certfile=self.cert_path,
            keyfile=self.key_path,
            host=self.host
        )
        self.remote.add_is_on_updated_callback(self._on_is_on_updated)
        self.remote.add_volume_info_updated_callback(self._on_volume_updated)
        self.remote.add_current_app_updated_callback(self._on_current_app_updated)
        self.remote.add_is_available_updated_callback(self._on_available_updated)

    def _on_is_on_updated(self, is_on: bool):
        self.is_on = is_on
        logger.info(f"TV is_on: {is_on}")

    def _on_volume_updated(self, volume_info):
        if volume_info:
            self.volume_level = getattr(volume_info, 'level', None)
            self.is_muted = getattr(volume_info, 'muted', None)
            logger.info(f"Volume: {self.volume_level}, Muted: {self.is_muted}")

    def _on_current_app_updated(self, current_app: str):
        self.current_app = current_app
        logger.info(f"Current app: {current_app}")

    def _on_available_updated(self, is_available: bool):
        self.connected = is_available
        logger.info(f"TV available: {is_available}")

    async def init_connection(self):
        async with self._lock:
            self._setup_remote()
            try:
                await self.remote.async_generate_cert_if_missing()
                await self.remote.async_connect()
                self.connected = True
                self.needs_pairing = False
                self.last_error = None
                self.remote.keep_reconnecting(self._on_invalid_auth)
                logger.info(f"Successfully connected to TV at {self.host}")
            except InvalidAuth:
                self.connected = False
                self.needs_pairing = True
                self.last_error = "Device needs pairing"
                logger.warning("TV authentication needed. Pairing required.")
            except CannotConnect as e:
                self.connected = False
                self.last_error = f"Cannot connect to TV at {self.host}: {e}"
                logger.error(self.last_error)
            except Exception as e:
                self.connected = False
                self.last_error = str(e)
                logger.error(f"Unexpected connection error: {e}")

    def _on_invalid_auth(self):
        self.connected = False
        self.needs_pairing = True
        logger.warning("Received invalid auth callback from TV")

    async def start_pairing(self):
        async with self._lock:
            self._setup_remote()
            await self.remote.async_generate_cert_if_missing()
            try:
                await self.remote.async_start_pairing()
                self.pairing_active = True
                self.last_error = None
                logger.info("Pairing challenge started. TV should display code.")
                return True
            except Exception as e:
                self.pairing_active = False
                self.last_error = f"Failed to start pairing: {e}"
                logger.error(self.last_error)
                raise e

    async def finish_pairing(self, code: str):
        async with self._lock:
            if not self.remote:
                raise RuntimeError("No active pairing session")
            try:
                await self.remote.async_finish_pairing(code.strip())
                self.pairing_active = False
                self.needs_pairing = False
                logger.info("Pairing finished successfully! Re-establishing connection...")
            except Exception as e:
                self.last_error = f"Pairing failed: {e}"
                logger.error(self.last_error)
                raise e

        # Reconnect with new auth credentials
        await self.init_connection()
        return self.connected

    async def send_key(self, key_name: str, direction: str = "SHORT"):
        if not self.remote or not self.connected:
            try:
                await self.init_connection()
            except Exception:
                pass
            if not self.connected:
                raise RuntimeError("TV is not connected. Please pair or check IP.")
        
        key_upper = key_name.upper().strip()
        try:
            self.remote.send_key_command(key_upper, direction)
            return True
        except ConnectionClosed:
            self.connected = False
            self.last_error = "Connection lost. Reconnecting..."
            logger.warning("Connection closed when sending key. Triggering reconnect.")
            asyncio.create_task(self.init_connection())
            raise RuntimeError("Connection lost to TV. Please retry in a second.")
        except Exception as e:
            logger.error(f"Error sending key {key_upper}: {e}")
            raise e

    async def send_text(self, text: str):
        if not self.remote or not self.connected:
            raise RuntimeError("TV is not connected.")
        try:
            self.remote.send_text(text)
            return True
        except Exception as e:
            logger.error(f"Error sending text: {e}")
            raise e

    async def launch_app(self, app_link: str):
        if not self.remote or not self.connected:
            raise RuntimeError("TV is not connected.")
        try:
            self.remote.send_launch_app_command(app_link)
            return True
        except Exception as e:
            logger.error(f"Error launching app {app_link}: {e}")
            raise e

    def set_host(self, new_host: str):
        self.host = new_host.strip()
        self.connected = False
        self.needs_pairing = False

tv_mgr = TVManager(DEFAULT_TV_IP)

# Request Models
class KeyRequest(BaseModel):
    key: str
    direction: Optional[str] = "SHORT"

class TextRequest(BaseModel):
    text: str

class LaunchRequest(BaseModel):
    app: str

class PairConfirmRequest(BaseModel):
    code: str

class IpRequest(BaseModel):
    ip: str

@app.on_event("startup")
async def startup_event():
    logger.info("Initializing background connection to TV...")
    asyncio.create_task(tv_mgr.init_connection())

@app.get("/api/status")
async def get_status():
    return {
        "tv_ip": tv_mgr.host,
        "local_ip": LOCAL_LAN_IP,
        "connected": tv_mgr.connected,
        "needs_pairing": tv_mgr.needs_pairing,
        "pairing_active": tv_mgr.pairing_active,
        "is_on": tv_mgr.is_on,
        "volume_level": tv_mgr.volume_level,
        "is_muted": tv_mgr.is_muted,
        "current_app": tv_mgr.current_app,
        "last_error": tv_mgr.last_error
    }

@app.post("/api/pair/start")
async def pair_start():
    try:
        await tv_mgr.start_pairing()
        return {"success": True, "message": "Pairing started! Check your TV screen for the code."}
    except Exception as e:
        return JSONResponse(status_code=500, content={"success": False, "error": str(e)})

@app.post("/api/pair/confirm")
async def pair_confirm(req: PairConfirmRequest):
    try:
        success = await tv_mgr.finish_pairing(req.code)
        return {"success": success, "message": "Paired successfully!" if success else "Pairing saved, connecting..."}
    except Exception as e:
        return JSONResponse(status_code=400, content={"success": False, "error": str(e)})

@app.post("/api/key")
async def send_key(req: KeyRequest):
    try:
        await tv_mgr.send_key(req.key, req.direction or "SHORT")
        return {"success": True, "key": req.key}
    except Exception as e:
        return JSONResponse(status_code=400, content={"success": False, "error": str(e)})

@app.post("/api/text")
async def send_text(req: TextRequest):
    try:
        await tv_mgr.send_text(req.text)
        return {"success": True, "text": req.text}
    except Exception as e:
        return JSONResponse(status_code=400, content={"success": False, "error": str(e)})

@app.post("/api/launch")
async def launch_app(req: LaunchRequest):
    try:
        await tv_mgr.launch_app(req.app)
        return {"success": True, "app": req.app}
    except Exception as e:
        return JSONResponse(status_code=400, content={"success": False, "error": str(e)})

@app.post("/api/reconnect")
async def reconnect():
    asyncio.create_task(tv_mgr.init_connection())
    return {"success": True, "message": "Reconnecting initiated"}

@app.post("/api/ip")
async def update_ip(req: IpRequest):
    tv_mgr.set_host(req.ip)
    asyncio.create_task(tv_mgr.init_connection())
    return {"success": True, "tv_ip": tv_mgr.host}

# Serve static frontend and PWA root files
STATIC_DIR = os.path.join(BASE_DIR, "static")
if os.path.exists(STATIC_DIR):
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

@app.get("/manifest.json")
async def get_manifest():
    return FileResponse(os.path.join(STATIC_DIR, "manifest.json"))

@app.get("/service-worker.js")
async def get_sw():
    return FileResponse(os.path.join(STATIC_DIR, "service-worker.js"), media_type="application/javascript")

@app.get("/")
async def read_index():
    index_file = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)
    return {"message": "Haier TV Remote Server is running. index.html not found."}

if __name__ == "__main__":
    port = 5000
    print(f"Starting Haier Smart TV Remote on http://localhost:{port} and http://{LOCAL_LAN_IP}:{port}")
    uvicorn.run("server:app", host="0.0.0.0", port=port, reload=False)
