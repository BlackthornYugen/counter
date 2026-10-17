import argparse
import sys
import uvicorn
from counter.config import HOST, PORT


def main():
    parser = argparse.ArgumentParser(description="Run the Counter WebSockets & REST Server")
    parser.add_argument("--host", default=HOST, help="Host to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=PORT, help="Port to bind (default: 8000)")
    parser.add_argument("--reload", action="store_true", help="Enable auto-reload for development")
    args = parser.parse_args()

    print(f"🚀 Starting Counter Server on http://{args.host}:{args.port}")
    uvicorn.run("counter.main:app", host=args.host, port=args.port, reload=args.reload)


if __name__ == "__main__":
    main()
