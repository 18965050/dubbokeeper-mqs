CREATE DATABASE `dubbo_keeper_dev` DEFAULT CHARACTER SET utf8 COLLATE utf8_unicode_ci;

use `dubbo_keeper_dev`;

CREATE TABLE `application` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL DEFAULT '',
  `type` varchar(50) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `应用名词索引` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

