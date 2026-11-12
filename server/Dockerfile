FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
ENV WW_DB=/data/data.db
VOLUME ["/data"]
EXPOSE 8600
# app.py 模块加载时幂等建表，直接启动即可
CMD ["gunicorn", "-w", "2", "-b", "0.0.0.0:8600", "--timeout", "15", "app:app"]
