
#include <fstream>
#include <iostream>
#include <vector>
#include <chrono>
#include "Mosaic.h"
class TestMosaic : public Mosaic {public: int framesCount(){return frames_size;}};
int main(int argc,char**argv){
 if(argc<7)return 2;
 int w=atoi(argv[1]),h=atoi(argv[2]),mode=atoi(argv[3]);
 TestMosaic mosaic;
 int init=mosaic.initialize(mode,Blend::STRIP_TYPE_WIDE,w,h,-1,false,0);
 std::cout<<"INIT "<<init<<std::endl;
 auto t=std::chrono::steady_clock::now();
 for(int i=5;i<argc;i++){
  std::ifstream in(argv[i],std::ios::binary);std::vector<unsigned char> rgb(w*h*3);
  if(!in.read((char*)rgb.data(),rgb.size()))return 3;
  int ret=mosaic.addFrameRGB(rgb.data());
  std::cout<<"FRAME "<<(i-5)<<" "<<ret<<" retained="<<mosaic.framesCount()<<std::endl;
 }
 float progress=0;bool cancel=false;int ret=mosaic.createMosaic(progress,cancel);
 int ow=0,oh=0;auto yvu=mosaic.getMosaic(ow,oh);
 std::cout<<"RESULT "<<ret<<" "<<ow<<" "<<oh<<" "<<std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count()<<std::endl;
 if(ret!=Mosaic::MOSAIC_RET_OK || !yvu || ow<=0 || oh<=0 || (long long)ow*oh>20000000)return 4;
 std::vector<unsigned char> bgr(ow*oh*3);ImageUtils::yvu2bgr(bgr.data(),yvu,ow,oh);
 std::ofstream out(argv[4],std::ios::binary);out.write((char*)bgr.data(),bgr.size());
}
