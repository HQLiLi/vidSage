-- MySQL dump 10.13  Distrib 8.0.37, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: vidsage
-- ------------------------------------------------------
-- Server version	8.0.37

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `vidsage`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `vidsage` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `vidsage`;

--
-- Table structure for table `intermediate_file`
--

DROP TABLE IF EXISTS `intermediate_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `intermediate_file` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
  `task_id` bigint NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
  `file_name` varchar(255) NOT NULL COMMENT '工作区内文件名；文件系统上限即 255',
  `file_type` varchar(32) NOT NULL COMMENT 'SOURCE_MEDIA / INFO_JSON / AUDIO',
  `status` varchar(32) NOT NULL COMMENT 'READY / SUPERSEDED / DELETED',
  `derived_from_run_id` bigint DEFAULT NULL COMMENT '产出它的那条 run（审计，无承重用途）',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '最近变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_intermediate_file_task_id_file_name` (`task_id`,`file_name`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中间产物（task 级缓存）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `intermediate_file`
--

LOCK TABLES `intermediate_file` WRITE;
/*!40000 ALTER TABLE `intermediate_file` DISABLE KEYS */;
INSERT INTO `intermediate_file` VALUES (1,1,'source.mp4','SOURCE_MEDIA','READY',114,'2026-10-19 21:36:22','2026-10-20 21:13:09'),(2,1,'source.info.json','INFO_JSON','READY',114,'2026-10-19 21:36:22','2026-10-20 21:13:09'),(3,1,'audio-16k1ch48k-t0.m4a','AUDIO','READY',114,'2026-10-19 21:36:22','2026-10-20 21:13:10'),(4,56,'source.mp4','SOURCE_MEDIA','READY',101,'2026-10-19 21:42:21','2026-10-19 21:42:21'),(5,56,'audio-16k1ch48k-t0.m4a','AUDIO','READY',101,'2026-10-19 21:42:21','2026-10-19 21:42:21'),(6,57,'source.mp4','SOURCE_MEDIA','READY',109,'2026-10-20 13:45:55','2026-10-20 13:52:37'),(7,57,'source.info.json','INFO_JSON','READY',109,'2026-10-20 13:45:55','2026-10-20 13:52:37'),(8,57,'audio-16k1ch48k-t0.m4a','AUDIO','READY',109,'2026-10-20 13:45:56','2026-10-20 13:52:37');
/*!40000 ALTER TABLE `intermediate_file` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `subtitle_artifact`
--

DROP TABLE IF EXISTS `subtitle_artifact`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subtitle_artifact` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
  `task_id` bigint NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
  `derived_from_run_id` bigint NOT NULL COMMENT '最后一条真正产出它的执行（审计；收口靠它定位）。跳过复用不刷新',
  `artifact_type` varchar(32) NOT NULL COMMENT 'ORIGINAL / CLEANED / TRANSLATED',
  `lang` varchar(16) NOT NULL DEFAULT '' COMMENT 'TRANSLATED 才填；空串 = 无语言维度',
  `revision` int NOT NULL COMMENT '同 (task,type,lang) 递增（MAX+1）',
  `status` varchar(32) NOT NULL COMMENT 'CREATING / READY / SUPERSEDED / FAILED / DELETED',
  `derived_from_artifact_id` bigint DEFAULT NULL COMMENT '审计 / 溯源：内容继承自哪一版上游。不参与级联、不参与读取判据',
  `keywords_fingerprint` varchar(64) DEFAULT NULL COMMENT '只有 CLEANED 有值',
  `segments_file_name` varchar(255) DEFAULT NULL COMMENT '检查点 JSON',
  `subtitles_file_name` varchar(255) DEFAULT NULL COMMENT 'SRT 派生视图',
  `context_file_name` varchar(255) DEFAULT NULL COMMENT '只有 CLEANED 有（摘要副产物）',
  `terms_file_name` varchar(255) DEFAULT NULL COMMENT '只有 TRANSLATED 有（术语查证副产物）',
  `segments_sha256` char(64) DEFAULT NULL COMMENT '成品文件的 sha256；与文件列同生同灭',
  `subtitles_sha256` char(64) DEFAULT NULL COMMENT '同上',
  `context_sha256` char(64) DEFAULT NULL COMMENT '同上',
  `terms_sha256` char(64) DEFAULT NULL COMMENT '同上',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `active_flag` varchar(32) GENERATED ALWAYS AS (if((`status` = _utf8mb4'READY'),`status`,NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subtitle_artifact_task_id_artifact_type_revision_lang` (`task_id`,`artifact_type`,`revision`,`lang`),
  UNIQUE KEY `uk_subtitle_artifact_task_id_artifact_type_lang_active_flag` (`task_id`,`artifact_type`,`lang`,`active_flag`),
  KEY `idx_subtitle_artifact_derived_from_run_id` (`derived_from_run_id`)
) ENGINE=InnoDB AUTO_INCREMENT=52 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字幕产物版本';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `subtitle_artifact`
--

LOCK TABLES `subtitle_artifact` WRITE;
/*!40000 ALTER TABLE `subtitle_artifact` DISABLE KEYS */;
INSERT INTO `subtitle_artifact` (`id`, `task_id`, `derived_from_run_id`, `artifact_type`, `lang`, `revision`, `status`, `derived_from_artifact_id`, `keywords_fingerprint`, `segments_file_name`, `subtitles_file_name`, `context_file_name`, `terms_file_name`, `segments_sha256`, `subtitles_sha256`, `context_sha256`, `terms_sha256`, `created_at`) VALUES (1,1,4,'ORIGINAL','',9,'FAILED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-18 17:15:22'),(2,1,75,'ORIGINAL','',10,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-18 22:52:13'),(3,1,75,'CLEANED','',1,'SUPERSEDED',2,'e3b0c44298fc1c14',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-18 22:52:13'),(4,1,77,'ORIGINAL','',11,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-18 22:56:38'),(5,1,77,'CLEANED','',2,'SUPERSEDED',4,'e3b0c44298fc1c14',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-18 22:56:38'),(6,1,1,'TRANSLATED','zh-Hant',1,'DELETED',5,NULL,'translated-segments-zh-hant.json','translated-subtitles-zh-hant.srt',NULL,'terms-zh-hant.json','deadbeef','deadbeef',NULL,'deadbeef','2026-10-19 12:24:53'),(7,1,78,'CLEANED','',3,'SUPERSEDED',4,'1173371b0eb5b30e',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:27:05'),(8,1,1,'TRANSLATED','zh-Hant',2,'DELETED',7,NULL,'translated-segments-zh-hant.json','translated-subtitles-zh-hant.srt',NULL,'terms-zh-hant.json','deadbeef','deadbeef',NULL,'deadbeef','2026-10-19 12:28:18'),(9,1,79,'ORIGINAL','',12,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:28:19'),(10,1,79,'CLEANED','',4,'SUPERSEDED',9,'1173371b0eb5b30e',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:28:19'),(11,1,80,'ORIGINAL','',13,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:29:03'),(12,1,80,'CLEANED','',5,'SUPERSEDED',11,'4cb894d35989cb49',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:29:03'),(13,1,81,'CLEANED','',6,'FAILED',11,'4cb894d35989cb49',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:32:11'),(14,1,82,'CLEANED','',7,'SUPERSEDED',11,'4cb894d35989cb49',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 12:33:06'),(15,1,84,'TRANSLATED','ja',1,'DELETED',14,NULL,'translated-segments-ja.json','translated-subtitles-ja.srt',NULL,'terms-ja.json','6b5765a7fc11929a77e85f755361dc8cc5184907a781dda8db7c1f10d59420aa','648650822c2a12a28819e964d10802c6d936c11ae25d9a047aa621491bb50e65',NULL,'dc7d9aa662661833212c61ee2cc6765cacf58d945d6656b8671a1f4bfda6b29a','2026-10-19 15:05:55'),(16,1,86,'TRANSLATED','en',1,'SUPERSEDED',14,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 15:06:17'),(17,1,87,'TRANSLATED','en',2,'DELETED',14,NULL,'translated-segments-en.json','translated-subtitles-en.srt',NULL,'terms-en.json','6799e0687014b14d16945a0fcc08f31fa375b9b7d00be8958d65321ee333b369','4590368d5a38a54f8f61a6c6eef6c0a597c5f4c5b91f6b7cb01f31f18ce25dd4',NULL,'dc7d9aa662661833212c61ee2cc6765cacf58d945d6656b8671a1f4bfda6b29a','2026-10-19 15:06:27'),(18,1,88,'TRANSLATED','zh-Hans',1,'DELETED',14,NULL,'translated-segments-zh-hans.json','translated-subtitles-zh-hans.srt',NULL,NULL,'b64bcb05f5e62925ae176d3b4023a254570abcdc096d13151ef0fd1055e8ecb6','4a3dce9235581a1de6e0bdf3c7b31610ce0d2f09ce0cd162d8a04c67d47a46ca',NULL,NULL,'2026-10-19 15:06:35'),(19,1,89,'CLEANED','',8,'SUPERSEDED',11,'a858b7a0641ba6c3',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 15:07:01'),(20,1,90,'TRANSLATED','ja',2,'DELETED',19,NULL,'translated-segments-ja.json','translated-subtitles-ja.srt',NULL,'terms-ja.json','713b1526220bc3aa39e1687c3947b079b47b27469d7a488d9364d133b4e1ecdf','f469727989b2ce3b287b31054bcc67f0fd9ac2dae277250a79a573a9fb24dd73',NULL,'27ddf36a72dd275b048e5596eda2c267b617650bd019b640f326054c736cd665','2026-10-19 15:07:24'),(21,1,91,'TRANSLATED','en',3,'DELETED',19,NULL,'translated-segments-en.json','translated-subtitles-en.srt',NULL,'terms-en.json','363bf65ecaa32025a4515686a3d85b750d06f9e3fe03a12882c2899d88c6638e','e1c9d4fd8e3dcdee9471f8493026edda8e63936f7d2aaf359775c4032553e0e6',NULL,'9e70edce14ca459e6c3a5689a61b3aabfcd48b4d24cc46e92f6710c579ad873f','2026-10-19 15:07:49'),(22,1,93,'TRANSLATED','zh-Hant',3,'SUPERSEDED',19,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 15:08:56'),(23,1,95,'TRANSLATED','zh-Hant',4,'DELETED',19,NULL,'translated-segments-zh-hant.json','translated-subtitles-zh-hant.srt',NULL,'terms-zh-hant.json','f224088a05c6ad44a18b2586a7376d9e9424b462a46099f31e1db9f7e8111097','923472bd8fcc28d6e84ed1b891566b90f352c73873911c4b3ae485c98585d0ee',NULL,'2622735af65c355d50c92fd68a7c5cee06b418ba11ea7314e9f6d49faf06db4a','2026-10-19 15:09:29'),(24,1,96,'ORIGINAL','',14,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:36:19'),(25,1,96,'CLEANED','',9,'SUPERSEDED',24,'a858b7a0641ba6c3',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:36:34'),(26,1,97,'ORIGINAL','',15,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:37:53'),(27,1,97,'CLEANED','',10,'SUPERSEDED',26,'a858b7a0641ba6c3',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:37:53'),(28,1,98,'ORIGINAL','',16,'SUPERSEDED',NULL,NULL,'original-segments.json','original-subtitles.srt',NULL,NULL,'c738d2b8adc51cb040c2128253bde51ef5e05b1d95a77cb6c662cc2ba68e97fe','c1237b5fbd1d2e1b8343bdff1936d7827b5b565de05a8ab945e3311a0d9744cc',NULL,NULL,'2026-10-19 21:39:01'),(29,1,98,'CLEANED','',11,'SUPERSEDED',28,'a858b7a0641ba6c3',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:39:20'),(30,1,99,'ORIGINAL','',17,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:40:55'),(31,1,99,'CLEANED','',12,'SUPERSEDED',30,'a858b7a0641ba6c3','clean-segments.json','clean-subtitles.srt','context.json',NULL,'1edeba2b2123e4b78cce63f8cd9dc368634ce6c1726e8a44f24f18c93f8c2ae6','4c4612f1af4fd1ca36ec1de3b5e6266cf920a80de2b800693e3983221543f92b','362c5aa7b5d924f029e4bfe13cbd402ad9f13fde0a10b381bf3827f8be2f4d98',NULL,'2026-10-19 21:41:02'),(32,1,100,'ORIGINAL','',18,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:41:46'),(33,1,100,'CLEANED','',13,'SUPERSEDED',32,'a858b7a0641ba6c3',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-19 21:41:46'),(34,56,101,'ORIGINAL','',1,'READY',NULL,NULL,'original-segments.json','original-subtitles.srt',NULL,NULL,'e7e2eebb53dffa5c505c857c87dd956b33c6e84b2a44908c23e63e016b9baa9b','83175a890e632ed325fd016fcac3125b0f5a3b5a3ff07271bb98feb2c223c7d2',NULL,NULL,'2026-10-19 21:42:19'),(35,56,101,'CLEANED','',1,'READY',34,'e3b0c44298fc1c14','clean-segments.json','clean-subtitles.srt','context.json',NULL,'30674ec8a7243152b86e1c28a5f6085cf42e3a38d2b90ce13a9806d3ed5778f7','b30517eea61c15c7082c9ca43186148dbfab42002f36fd3cacc5c70771cf03e3','c273d6f9af0ffdd39a10b3a9b13ff51ffffb2d4456d5318b1343715b7d59aa7a',NULL,'2026-10-19 21:42:35'),(36,57,102,'ORIGINAL','',1,'SUPERSEDED',NULL,NULL,'original-segments.json','original-subtitles.srt',NULL,NULL,'b97547a66d7d0d9fb6773b68512c46730f927308ac38f138b38bab538f5d4d12','734f16768d3c1cc6400e21909a660e60230be60e34c4e3517d4ef86881f3f872',NULL,NULL,'2026-10-20 13:45:51'),(37,57,102,'CLEANED','',1,'SUPERSEDED',36,'e3b0c44298fc1c14',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 13:46:03'),(38,57,103,'TRANSLATED','en',1,'DELETED',37,NULL,'translated-segments-en.json','translated-subtitles-en.srt',NULL,'terms-en.json','e19bb4a4cd7a800a5dacdfa99bbb4d370a2e73687aa43d4779c65067e8c0adc4','bb9311c57a4d7ac2538cef35baa7bee415bc3f44f621dada5cd66dbe68f8f5cc',NULL,'c62e9611ac7543aead86f8da6c1674e8f407d73979678b61cf3285453de3548b','2026-10-20 13:47:07'),(39,57,105,'ORIGINAL','',2,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 13:48:50'),(40,57,106,'CLEANED','',2,'SUPERSEDED',39,'1173371b0eb5b30e',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 13:50:12'),(41,57,107,'TRANSLATED','en',2,'DELETED',40,NULL,'translated-segments-en.json','translated-subtitles-en.srt',NULL,'terms-en.json','ae2dd0fe8fd6f7b1227c48b44fb2fa43cd68790831d608b98c75dad0056a3919','c0c21b8df587ab1278d4bfce0e143c759e481f872cc0bca9880499255ca539e8',NULL,'aca2b4cf31e5d2a5c91bb6060f604f62e96b9f1bdc1c36ad67c51aaa62c30667','2026-10-20 13:51:23'),(42,57,108,'CLEANED','',3,'SUPERSEDED',39,'1173371b0eb5b30e',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 13:51:53'),(43,57,109,'ORIGINAL','',3,'READY',NULL,NULL,'original-segments.json','original-subtitles.srt',NULL,NULL,'3504ff9e5cfff4a08b5d64e5ef22eb528453f0a900398b403e7362ebba6f0b75','3be2d246b063bc8be5cbdda966f9b0cc5b69587a67577b8ae2658b0073f89eba',NULL,NULL,'2026-10-20 13:52:33'),(44,57,109,'CLEANED','',4,'READY',43,'1173371b0eb5b30e','clean-segments.json','clean-subtitles.srt','context.json',NULL,'0feaab5b79b4ca6b2d8117e9c208c98ca0b0c6d52aae46da40a22a914cf5555c','02fb5b5db05be8ab2803816ff02fec6920b820fc8724003ba5496295a25d69d5','da58654874fbc76dc8619c9f6743bef6b4aa81163f47727e7d571bd4e244531b',NULL,'2026-10-20 13:52:43'),(45,1,111,'ORIGINAL','',19,'SUPERSEDED',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 20:30:39'),(46,1,112,'TRANSLATED','en',4,'DELETED',33,NULL,'translated-segments-en.json','translated-subtitles-en.srt',NULL,'terms-en.json','c838c85a624d5b7082510b968a633b4dab94bc58c2a1fe14e03fb93b8d342220','891d8e6418201443fde141be973e89e3158aa15994b88e93a2d8cf67e019b3e7',NULL,'a31c800b036ad1163eca71d5a1fbc916cdbaef57fb7ae6906151bb3eab92c796','2026-10-20 20:33:12'),(47,1,113,'CLEANED','',14,'SUPERSEDED',45,'94eb7509285a877f',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 20:39:56'),(48,1,114,'ORIGINAL','',20,'READY',NULL,NULL,'original-segments.json','original-subtitles.srt',NULL,NULL,'ad72e01bc06de2c9924b35635631fda5e3d4d9be87a73d8b468383b581e52624','021da6073327f48b2616181781e300170eff55bb7eccb8ecb4d9c183d0855a51',NULL,NULL,'2026-10-20 21:13:06'),(49,1,114,'CLEANED','',15,'SUPERSEDED',48,'94eb7509285a877f',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-20 21:13:17'),(50,1,115,'TRANSLATED','en',5,'DELETED',49,NULL,'translated-segments-en.json','translated-subtitles-en.srt',NULL,'terms-en.json','93ba48e11e23bcda96c30a7896b1fe0f56bea561744450d4c3bea32668c42bc8','128e738d10135a3dfa25e6e91fd30679f11d428505ae2ad4bb852bba40cdd5cc',NULL,'e7e144461403b10708723048155190eccdc6f23a38d38fab6214673f8f59cf44','2026-10-20 21:17:32'),(51,1,116,'CLEANED','',16,'READY',48,'9b192f676f7bc594','clean-segments.json','clean-subtitles.srt','context.json',NULL,'288370759ec8ac43c4bbd74758fdaaa17d4100769f380a8a3b7925081022b45c','d1ea1bc7d43c782e88044732c491b1a7021c4f46bc0f73961dfe9276672ec247','1570e1981e123170d3f122e53b6f94ac382412fe00e2dd697367937d43304852',NULL,'2026-10-20 21:18:34');
/*!40000 ALTER TABLE `subtitle_artifact` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `subtitle_keyword`
--

DROP TABLE IF EXISTS `subtitle_keyword`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subtitle_keyword` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
  `task_id` bigint NOT NULL COMMENT '逻辑外键 → subtitle_task.id（原为 workspace_key）',
  `keywords` varchar(4096) NOT NULL COMMENT 'U+0001 连接，顺序即用户填写顺序',
  `created_at` datetime NOT NULL COMMENT '首次写入时间（覆盖写不刷新）',
  `updated_at` datetime NOT NULL COMMENT '最近一次覆盖写的时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subtitle_keyword_task_id` (`task_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字幕关键词（当前生效一份）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `subtitle_keyword`
--

LOCK TABLES `subtitle_keyword` WRITE;
/*!40000 ALTER TABLE `subtitle_keyword` DISABLE KEYS */;
INSERT INTO `subtitle_keyword` VALUES (1,1,'GAMMA','2026-10-18 17:08:26','2026-10-20 21:18:34'),(4,2,'影视飓风李子柒','2026-10-18 17:11:35','2026-10-18 17:11:35'),(8,57,'影视飓风','2026-10-20 13:50:10','2026-10-20 13:51:53');
/*!40000 ALTER TABLE `subtitle_keyword` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `subtitle_task`
--

DROP TABLE IF EXISTS `subtitle_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `subtitle_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
  `task_no` varchar(32) NOT NULL COMMENT '对外稳定号（32 位短 UUID）',
  `workspace_id` bigint NOT NULL COMMENT '逻辑外键 → workspace.id',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '最近一次 run 收尾的时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subtitle_task_task_no` (`task_no`),
  UNIQUE KEY `uk_subtitle_task_workspace_id` (`workspace_id`)
) ENGINE=InnoDB AUTO_INCREMENT=58 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字幕任务';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `subtitle_task`
--

LOCK TABLES `subtitle_task` WRITE;
/*!40000 ALTER TABLE `subtitle_task` DISABLE KEYS */;
INSERT INTO `subtitle_task` VALUES (1,'df52406708914c099100db031624ea31',1,'2026-10-18 17:04:04','2026-10-20 21:18:38'),(2,'e32d828b9d36446486dc2bbce7c4e278',2,'2026-10-18 17:10:11','2026-10-18 17:11:47'),(3,'2fbdc7facc97468389b16ac4df598cbe',3,'2026-10-18 17:31:16','2026-10-18 17:31:19'),(4,'6aeedd905d624dfab722e9d1f2567d24',4,'2026-10-18 17:31:18','2026-10-18 17:31:19'),(5,'e8ec13083d994d73a796d54890b836aa',5,'2026-10-18 17:31:18','2026-10-18 17:31:21'),(6,'abee042a65004bb0bbf7165e661b4f42',6,'2026-10-18 17:31:19','2026-10-18 17:31:21'),(7,'8757ea2cb9b0496087943f084bc76e94',7,'2026-10-18 17:31:19','2026-10-18 17:31:22'),(8,'f6726389a29e4bbf87e861c92d5f1df0',8,'2026-10-18 17:31:44','2026-10-18 17:31:45'),(16,'98b188c80f914055bc1870807e8b71d9',16,'2026-10-18 17:31:44','2026-10-18 17:31:45'),(24,'597340a014684dc2a26350d7608607e1',24,'2026-10-18 17:31:44','2026-10-18 17:31:47'),(32,'19ade0c280584f2890dd4a729228b3f5',32,'2026-10-18 18:04:55','2026-10-18 18:04:57'),(40,'1e2acd3299a74fec956f4f10b0fc747d',40,'2026-10-18 18:04:56','2026-10-18 18:04:58'),(48,'3b62b5a603d2470cb0ddc2e4b8d9c620',48,'2026-10-18 18:04:56','2026-10-18 18:04:58'),(56,'1470a57845c84ba68bf1a3b757ffdf70',56,'2026-10-19 21:42:19','2026-10-19 21:42:38'),(57,'6613e34534db4773a41fe9ce623ebc1e',57,'2026-10-20 13:45:42','2026-10-20 13:52:48');
/*!40000 ALTER TABLE `subtitle_task` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `task_run`
--

DROP TABLE IF EXISTS `task_run`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
  `task_id` bigint NOT NULL COMMENT '逻辑外键 → subtitle_task.id',
  `run_no` int NOT NULL COMMENT '任务内递增（MAX+1，不需要事务）',
  `run_type` varchar(32) NOT NULL COMMENT 'MAIN / RECLEAN / TRANSLATE',
  `target_language` varchar(16) DEFAULT NULL COMMENT '只有 TRANSLATE 有值',
  `status` varchar(32) NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED / INTERRUPTED',
  `current_stage` varchar(64) DEFAULT NULL COMMENT '前端「卡在哪一步」',
  `error_message` text COMMENT '失败原因（不用中文前缀区分来源）',
  `started_at` datetime DEFAULT NULL COMMENT '插 RUNNING 行时填 now()',
  `finished_at` datetime DEFAULT NULL COMMENT '落终态时填 now()；INTERRUPTED 填启动收口的时刻',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `active_flag` varchar(32) GENERATED ALWAYS AS (if((`status` = _utf8mb4'RUNNING'),_utf8mb4'RUNNING',NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_run_task_id_run_no` (`task_id`,`run_no`),
  UNIQUE KEY `uk_task_run_task_id_active_flag` (`task_id`,`active_flag`)
) ENGINE=InnoDB AUTO_INCREMENT=117 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每次执行';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `task_run`
--

LOCK TABLES `task_run` WRITE;
/*!40000 ALTER TABLE `task_run` DISABLE KEYS */;
INSERT INTO `task_run` (`id`, `task_id`, `run_no`, `run_type`, `target_language`, `status`, `current_stage`, `error_message`, `started_at`, `finished_at`, `created_at`) VALUES (1,1,1,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:04:04','2026-10-18 17:04:28','2026-10-18 17:04:04'),(2,1,2,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:05:10','2026-10-18 17:05:10','2026-10-18 17:05:10'),(3,1,3,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:08:26','2026-10-18 17:08:28','2026-10-18 17:08:26'),(4,1,4,'TRANSLATE','en','INTERRUPTED','translate-subtitles',NULL,'2026-10-18 17:09:37','2026-10-18 17:16:38','2026-10-18 17:09:37'),(5,2,1,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:10:11','2026-10-18 17:11:17','2026-10-18 17:10:11'),(8,1,5,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:10:40','2026-10-18 17:10:43','2026-10-18 17:10:40'),(9,1,6,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:11:15','2026-10-18 17:11:18','2026-10-18 17:11:15'),(10,2,2,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 17:11:35','2026-10-18 17:11:47','2026-10-18 17:11:35'),(11,1,99,'MAIN',NULL,'INTERRUPTED','acquire-media',NULL,'2026-10-18 17:20:21','2026-10-18 17:21:09','2026-10-18 17:20:21'),(12,3,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1probe1zz: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:16','2026-10-18 17:31:17','2026-10-18 17:31:16'),(14,3,2,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1probe1zz: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:18','2026-10-18 17:31:19','2026-10-18 17:31:18'),(15,4,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1probe2zz: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:18','2026-10-18 17:31:19','2026-10-18 17:31:18'),(18,5,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1probe3zz: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:18','2026-10-18 17:31:21','2026-10-18 17:31:18'),(21,6,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1probe4zz: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:19','2026-10-18 17:31:21','2026-10-18 17:31:19'),(24,7,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1probe5zz: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:19','2026-10-18 17:31:22','2026-10-18 17:31:19'),(27,8,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1tight1q: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:44','2026-10-18 17:31:45','2026-10-18 17:31:44'),(35,16,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1tight2q: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:44','2026-10-18 17:31:45','2026-10-18 17:31:44'),(43,24,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1tight3q: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 17:31:44','2026-10-18 17:31:47','2026-10-18 17:31:44'),(51,32,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1fix1q: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 18:04:55','2026-10-18 18:04:57','2026-10-18 18:04:55'),(59,40,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1fix2q: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 18:04:56','2026-10-18 18:04:58','2026-10-18 18:04:56'),(67,48,1,'MAIN',NULL,'FAILED','acquire-media','yt-dlp 下载失败（exit=1）：ERROR: [BiliBili] 1fix3q: Unable to download webpage: HTTP Error 404: Not Found (caused by <HTTPError 404: Not Found>)','2026-10-18 18:04:56','2026-10-18 18:04:58','2026-10-18 18:04:56'),(75,1,100,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 22:52:13','2026-10-18 22:52:13','2026-10-18 22:52:13'),(76,1,101,'TRANSLATE','zh-Hant','SUCCEEDED','translate-subtitles',NULL,'2026-10-18 22:55:47','2026-10-18 22:55:56','2026-10-18 22:55:47'),(77,1,102,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-18 22:56:38','2026-10-18 22:56:38','2026-10-18 22:56:38'),(78,1,103,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 12:27:05','2026-10-19 12:27:10','2026-10-19 12:27:05'),(79,1,104,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 12:28:19','2026-10-19 12:28:19','2026-10-19 12:28:19'),(80,1,105,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 12:29:03','2026-10-19 12:29:08','2026-10-19 12:29:03'),(81,1,106,'RECLEAN',NULL,'FAILED','clean-subtitles','原始字幕 segments 解析失败：original-segments.json；原因：Unrecognized token \'not\': was expecting (JSON String, Number, Array, Object or token \'null\', \'true\' or \'false\')\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); byte offset: #0]','2026-10-19 12:32:11','2026-10-19 12:32:11','2026-10-19 12:32:11'),(82,1,107,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 12:33:06','2026-10-19 12:33:07','2026-10-19 12:33:06'),(84,1,108,'TRANSLATE','ja','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:05:46','2026-10-19 15:05:55','2026-10-19 15:05:46'),(85,1,109,'TRANSLATE','ja','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:06:04','2026-10-19 15:06:04','2026-10-19 15:06:04'),(86,1,110,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:06:10','2026-10-19 15:06:17','2026-10-19 15:06:10'),(87,1,111,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:06:24','2026-10-19 15:06:27','2026-10-19 15:06:24'),(88,1,112,'TRANSLATE','zh-Hans','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:06:35','2026-10-19 15:06:35','2026-10-19 15:06:35'),(89,1,113,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 15:07:01','2026-10-19 15:07:04','2026-10-19 15:07:01'),(90,1,114,'TRANSLATE','ja','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:07:16','2026-10-19 15:07:24','2026-10-19 15:07:16'),(91,1,115,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:07:41','2026-10-19 15:07:49','2026-10-19 15:07:41'),(93,1,116,'TRANSLATE','zh-Hant','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:08:47','2026-10-19 15:08:56','2026-10-19 15:08:47'),(94,1,117,'TRANSLATE','zh-Hant','FAILED','translate-subtitles','产物写入失败：F:\\personal_project\\vidSage\\backend\\.\\data\\works\\BV11QMd6WEJp_1\\translated-segments-zh-hant.json；请检查磁盘空间与目录写权限。原因：F:\\personal_project\\vidSage\\backend\\.\\data\\works\\BV11QMd6WEJp_1\\translated-segments-zh-hant.json','2026-10-19 15:09:10','2026-10-19 15:09:12','2026-10-19 15:09:10'),(95,1,118,'TRANSLATE','zh-Hant','SUCCEEDED','translate-subtitles',NULL,'2026-10-19 15:09:27','2026-10-19 15:09:29','2026-10-19 15:09:27'),(96,1,119,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 21:36:10','2026-10-19 21:36:37','2026-10-19 21:36:10'),(97,1,120,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 21:37:51','2026-10-19 21:37:53','2026-10-19 21:37:51'),(98,1,121,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 21:38:59','2026-10-19 21:39:23','2026-10-19 21:38:59'),(99,1,122,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 21:40:52','2026-10-19 21:41:02','2026-10-19 21:40:52'),(100,1,123,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 21:41:44','2026-10-19 21:41:49','2026-10-19 21:41:44'),(101,56,1,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-19 21:42:19','2026-10-19 21:42:38','2026-10-19 21:42:19'),(102,57,1,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 13:45:42','2026-10-20 13:46:10','2026-10-20 13:45:42'),(103,57,2,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-20 13:46:57','2026-10-20 13:47:07','2026-10-20 13:46:57'),(104,57,3,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 13:47:47','2026-10-20 13:47:49','2026-10-20 13:47:47'),(105,57,4,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 13:48:48','2026-10-20 13:48:55','2026-10-20 13:48:48'),(106,57,5,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 13:50:10','2026-10-20 13:50:17','2026-10-20 13:50:10'),(107,57,6,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-20 13:51:14','2026-10-20 13:51:23','2026-10-20 13:51:14'),(108,57,7,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 13:51:53','2026-10-20 13:51:56','2026-10-20 13:51:53'),(109,57,8,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 13:52:31','2026-10-20 13:52:48','2026-10-20 13:52:31'),(110,1,124,'TRANSLATE','en','INTERRUPTED','translate-subtitles',NULL,'2026-10-20 20:02:57','2026-10-20 20:08:11','2026-10-20 20:02:57'),(111,1,125,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 20:30:28','2026-10-20 20:30:52','2026-10-20 20:30:28'),(112,1,126,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-20 20:33:05','2026-10-20 20:33:12','2026-10-20 20:33:05'),(113,1,127,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 20:39:56','2026-10-20 20:39:59','2026-10-20 20:39:56'),(114,1,128,'MAIN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 21:13:04','2026-10-20 21:13:20','2026-10-20 21:13:04'),(115,1,129,'TRANSLATE','en','SUCCEEDED','translate-subtitles',NULL,'2026-10-20 21:17:24','2026-10-20 21:17:32','2026-10-20 21:17:24'),(116,1,130,'RECLEAN',NULL,'SUCCEEDED','clean-subtitles',NULL,'2026-10-20 21:18:34','2026-10-20 21:18:38','2026-10-20 21:18:34');
/*!40000 ALTER TABLE `task_run` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `workspace`
--

DROP TABLE IF EXISTS `workspace`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `workspace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '内部自增行 id，不对外',
  `source_type` varchar(32) NOT NULL COMMENT 'URL / UPLOAD',
  `source_key` varchar(512) NOT NULL COMMENT 'URL 路线：bvid|p；上传路线：内容 sha256',
  `source_uri` varchar(2048) NOT NULL COMMENT 'URL 原文 / 上传文件原始名。红线：不得写日志',
  `canonical_url` varchar(1024) DEFAULT NULL COMMENT '规范化后的 URL，只给人看',
  `duration_ms` int DEFAULT NULL COMMENT '内容指纹之一（URL 路线）；UPLOAD 留空',
  `published_at` datetime DEFAULT NULL COMMENT '内容指纹之二，来自 yt-dlp 的 timestamp',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '最近变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_source_type_source_key` (`source_type`,`source_key`)
) ENGINE=InnoDB AUTO_INCREMENT=58 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='媒体身份';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `workspace`
--

LOCK TABLES `workspace` WRITE;
/*!40000 ALTER TABLE `workspace` DISABLE KEYS */;
INSERT INTO `workspace` VALUES (1,'URL','BV11QMd6WEJp|1','https://www.bilibili.com/video/BV11QMd6WEJp?spm_id_from=333.999','https://www.bilibili.com/video/BV11QMd6WEJp',67766,'2026-08-03 18:52:18','2026-10-18 17:04:04','2026-10-20 21:13:06'),(2,'URL','BV1xx411c7mD|1','https://www.bilibili.com/video/BV1xx411c7mD','https://www.bilibili.com/video/BV1xx411c7mD',NULL,NULL,'2026-10-18 17:10:11','2026-10-18 17:10:11'),(3,'URL','BV1probe1zz|1','https://www.bilibili.com/video/BV1probe1zz','https://www.bilibili.com/video/BV1probe1zz',NULL,NULL,'2026-10-18 17:31:16','2026-10-18 17:31:16'),(4,'URL','BV1probe2zz|1','https://www.bilibili.com/video/BV1probe2zz','https://www.bilibili.com/video/BV1probe2zz',NULL,NULL,'2026-10-18 17:31:18','2026-10-18 17:31:18'),(5,'URL','BV1probe3zz|1','https://www.bilibili.com/video/BV1probe3zz','https://www.bilibili.com/video/BV1probe3zz',NULL,NULL,'2026-10-18 17:31:18','2026-10-18 17:31:18'),(6,'URL','BV1probe4zz|1','https://www.bilibili.com/video/BV1probe4zz','https://www.bilibili.com/video/BV1probe4zz',NULL,NULL,'2026-10-18 17:31:19','2026-10-18 17:31:19'),(7,'URL','BV1probe5zz|1','https://www.bilibili.com/video/BV1probe5zz','https://www.bilibili.com/video/BV1probe5zz',NULL,NULL,'2026-10-18 17:31:19','2026-10-18 17:31:19'),(8,'URL','BV1tight1q|1','https://www.bilibili.com/video/BV1tight1q','https://www.bilibili.com/video/BV1tight1q',NULL,NULL,'2026-10-18 17:31:44','2026-10-18 17:31:44'),(16,'URL','BV1tight2q|1','https://www.bilibili.com/video/BV1tight2q','https://www.bilibili.com/video/BV1tight2q',NULL,NULL,'2026-10-18 17:31:44','2026-10-18 17:31:44'),(24,'URL','BV1tight3q|1','https://www.bilibili.com/video/BV1tight3q','https://www.bilibili.com/video/BV1tight3q',NULL,NULL,'2026-10-18 17:31:44','2026-10-18 17:31:44'),(32,'URL','BV1fix1q|1','https://www.bilibili.com/video/BV1fix1q','https://www.bilibili.com/video/BV1fix1q',NULL,NULL,'2026-10-18 18:04:55','2026-10-18 18:04:55'),(40,'URL','BV1fix2q|1','https://www.bilibili.com/video/BV1fix2q','https://www.bilibili.com/video/BV1fix2q',NULL,NULL,'2026-10-18 18:04:56','2026-10-18 18:04:56'),(48,'URL','BV1fix3q|1','https://www.bilibili.com/video/BV1fix3q','https://www.bilibili.com/video/BV1fix3q',NULL,NULL,'2026-10-18 18:04:56','2026-10-18 18:04:56'),(56,'UPLOAD','9466c90f283671b96028f981c39e70c8ab3b22a67911e83859581543278a8dfc','source.mp4',NULL,NULL,NULL,'2026-10-19 21:42:19','2026-10-19 21:42:19'),(57,'URL','BV1Va7V66Ebp|1','https://www.bilibili.com/video/BV1Va7V66Ebp/?spm_id_from=333.1387.homepage.video_card.click&vd_source=60a7fe67b4220e755961a42c50ce321b','https://www.bilibili.com/video/BV1Va7V66Ebp/',134095,'2026-06-26 13:31:29','2026-10-20 13:45:42','2026-10-20 13:52:33');
/*!40000 ALTER TABLE `workspace` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-20 14:08:42
